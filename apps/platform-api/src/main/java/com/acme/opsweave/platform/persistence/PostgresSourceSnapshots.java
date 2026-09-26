package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.inventory.api.*;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.platform.inventory.*;
import com.acme.opsweave.sharedkernel.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

/** Source lock -> sorted entity locks; one bounded transaction commits observations, presence and receipt. */
final class PostgresSourceSnapshots implements SourceSnapshotStore {
    private final DataSource dataSource;
    PostgresSourceSnapshots(DataSource dataSource){this.dataSource=dataSource;}
    static final String LIVE = """
        EXISTS (SELECT 1 FROM inventory.entity_source_presence p JOIN inventory.asset_identity i
          ON i.tenant_id=p.tenant_id AND i.id=p.identity_id AND i.entity_id=p.entity_id AND i.active
          WHERE p.tenant_id=e.tenant_id AND p.entity_id=e.id AND p.present
          AND p.expires_epoch_nanos>extract(epoch from statement_timestamp())*1000000000)
        """;
    // Expiry/revocation is evaluated at read time before lifecycle filtering and pagination.
    static final String ENTITY_VIEW = """
        (SELECT e.id,e.tenant_id,e.entity_type,e.name,e.version,e.attributes,
          CASE WHEN EXISTS(SELECT 1 FROM inventory.entity_source_authority a WHERE a.tenant_id=e.tenant_id AND a.entity_id=e.id
             AND a.primary_snapshot->>'lifecycle'='INACTIVE') THEN CASE WHEN %s THEN 'ACTIVE' ELSE 'INACTIVE' END
             ELSE e.lifecycle END AS lifecycle FROM inventory.entity e) inventory_view
        """.formatted(LIVE);
    static Entity project(Connection c,Entity primary,SourceReview review,long version)throws SQLException{
        var p=FieldAuthority.project(primary,review,version);
        if(primary.lifecycle()!=Lifecycle.INACTIVE)return p;
        try(var s=c.prepareStatement("SELECT "+LIVE+" FROM inventory.entity e WHERE e.tenant_id=? AND e.id=?")){
            s.setString(1,primary.tenantId().value());s.setObject(2,primary.id().value());try(var r=s.executeQuery()){
                if(r.next() && r.getBoolean(1))return new Entity(p.id(),p.tenantId(),p.entityType(),p.name(),Lifecycle.ACTIVE,p.version(),p.lastSeen(),p.attributes());
            }
        }return p;
    }
    @Override public SourceSnapshot.Receipt ingest(TenantId tenant,String actor,String source,String namespace,SourceSnapshot.Input input,String digest){
        SourceSnapshot.Receipt[] result={null};
        Transactions.run(dataSource,c->result[0]=ingestInside(c,tenant,actor,source,namespace,input,digest));return result[0];
    }
    private SourceSnapshot.Receipt ingestInside(Connection c,TenantId tenant,String actor,String source,String namespace,SourceSnapshot.Input input,String digest)throws SQLException{
        var scope=new SourceScan.Scope(tenant,source,"cmdb-host");AssetIdentity.namespace(namespace);SourceReview.bounded(actor,128);
            PostgresSourceScans.lock(c,scope);
            var prior=read(c,tenant,source,input.requestId());
            if(prior!=null){if(!prior.actor().equals(actor) || !prior.namespace().equals(namespace) || !prior.input().equals(input) || !prior.mappingDigest().equals(digest))throw new SourceSnapshot.Conflict(SourceSnapshot.Code.REQUEST_CONFLICT);return prior;}
            Instant now=PostgresSourceScans.now(c);input.requireFresh(now);
            var lease=SourceScan.Lease.acquire(scope,input.requestId(),PostgresSourceScans.read(c,scope),now);PostgresSourceScans.write(c,lease);
            try(var s=c.prepareStatement("SELECT count(*),max(observed_epoch_nanos) FROM inventory.source_snapshot WHERE tenant_id=? AND source_instance_id=?")){
                bind(s,tenant,source);try(var r=s.executeQuery()){r.next();if(r.getLong(1)>=SourceSnapshot.MAX_RECEIPTS)throw new SourceSnapshot.Conflict(SourceSnapshot.Code.SNAPSHOT_LIMIT);
                    if(r.getBigDecimal(2)!=null && r.getBigDecimal(2).compareTo(PostgresInventoryStore.nanos(input.observedAt()))>=0)throw new SourceSnapshot.Conflict(SourceSnapshot.Code.SNAPSHOT_OUTDATED);}}
            var previous=new LinkedHashMap<String,SourceSnapshot.Presence>();
            try(var s=c.prepareStatement("SELECT body FROM inventory.entity_source_presence WHERE tenant_id=? AND source_instance_id=? ORDER BY external_id LIMIT 101")){
                bind(s,tenant,source);try(var r=s.executeQuery()){while(r.next()){var p=SourceSnapshotJson.presence(r.getString(1));previous.put(p.externalId(),p);}}}
            var inputs=new LinkedHashMap<String,AssetIdentity>();var targets=new TreeSet<EntityId>(Comparator.comparing(v->v.value().toString()));
            if(previous.values().stream().anyMatch(p->!p.identity().namespace().equals(namespace)))throw new SourceSnapshot.Conflict(SourceSnapshot.Code.BINDING_CONFLICT);
            for(var row:input.records()){
                try(var s=c.prepareStatement("SELECT body FROM inventory.asset_identity WHERE tenant_id=? AND namespace=? AND value=? AND active")){
                    s.setString(1,tenant.value());s.setString(2,namespace);s.setString(3,row.assetUuid());try(var r=s.executeQuery()){
                        if(!r.next())throw new SourceSnapshot.Conflict(SourceSnapshot.Code.IDENTITY_UNRESOLVED);
                        var identity=AssetIdentityJson.decode(r.getString(1));inputs.put(row.externalId(),identity);if(!targets.add(identity.entityId()))throw new SourceSnapshot.Conflict(SourceSnapshot.Code.BINDING_CONFLICT);
                    }}
                var old=previous.get(row.externalId());var identity=inputs.get(row.externalId());
                if(old!=null && (!old.entityId().equals(identity.entityId()) || !old.identity().equals(identity.pin())))throw new SourceSnapshot.Conflict(SourceSnapshot.Code.BINDING_CONFLICT);
                if(previous.values().stream().anyMatch(p->p.entityId().equals(identity.entityId()) && !p.externalId().equals(row.externalId())))throw new SourceSnapshot.Conflict(SourceSnapshot.Code.BINDING_CONFLICT);
            }
            var allIds=new HashSet<>(previous.keySet());allIds.addAll(inputs.keySet());if(allIds.size()>SourceSnapshot.MAX_RECORDS)throw new SourceSnapshot.Conflict(SourceSnapshot.Code.SNAPSHOT_LIMIT);
            if(input.complete())previous.values().forEach(p->targets.add(p.entityId()));
            for(var entity:targets)PostgresSourceReviews.lock(c,tenant,entity);
            for(var row:input.records()){
                try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){
                    s.setString(1,"source-review-binding:"+tenant.value().length()+":"+tenant.value()+":"+source.length()+":"+source+":"+row.externalId());s.execute();}
                try(var s=c.prepareStatement("SELECT entity_id,external_id FROM inventory.source_review WHERE tenant_id=? AND source_instance_id=? AND (external_id=? OR entity_id=?) AND active")){
                    bind(s,tenant,source);s.setString(3,row.externalId());s.setObject(4,inputs.get(row.externalId()).entityId().value());try(var r=s.executeQuery()){while(r.next())if(!r.getObject(1,UUID.class).equals(inputs.get(row.externalId()).entityId().value()) || !r.getString(2).equals(row.externalId()))throw new SourceSnapshot.Conflict(SourceSnapshot.Code.BINDING_CONFLICT);}}
            }
            var resolved=new ArrayList<SourceSnapshot.Resolved>();
            for(var row:input.records()){
                var identity=inputs.get(row.externalId());var entity=identity.entityId();PostgresAssetIdentities.validatePin(c,tenant,entity,identity.pin());
                var current=PostgresSourceReviews.entity(c,tenant,entity);var primary=PostgresSourceReviews.primary(c,tenant,entity);if(primary==null){primary=current;PostgresSourceReviews.primary(c,primary);}
                UUID reviewId=UUID.nameUUIDFromBytes((input.requestId()+"|"+row.externalId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                var staged=new SourceReviewStore.Import(reviewId,current.version()+1,new ExternalObjectKey(tenant,source,"cmdb-host",row.externalId(),"1"),input.observedAt(),row.values(),digest,actor,identity.pin());
                var review=staged.stage(primary,current.version()+1,now);
                try(var s=c.prepareStatement("INSERT INTO inventory.source_review(tenant_id,id,entity_id,source_instance_id,external_id,body) VALUES(?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING")){
                    s.setString(1,tenant.value());s.setObject(2,reviewId);s.setObject(3,entity.value());s.setString(4,source);s.setString(5,row.externalId());s.setString(6,SourceReviewJson.encode(review));if(s.executeUpdate()!=1)throw new SourceSnapshot.Conflict(SourceSnapshot.Code.REQUEST_CONFLICT);}
                var observed=new HashMap<String,Object>(row.values());if(observed.containsKey("name"))observed.put("entityName",observed.remove("name"));
                observed.put("dataMode","import");observed.put("presence","present");observed.put("mappingDigest",digest);observed.put("resolverEngine",SourceSnapshot.ENGINE);observed.put("identity",AssetIdentityJson.pin(identity.pin()));
                PostgresInventoryStore.insertObservation(c,new Observation("source-snapshot:"+reviewId,review.source(),entity,input.observedAt(),now,observed,"source-snapshot:"+input.requestId()+":"+reviewId,1));
                write(c,new SourceSnapshot.Presence(tenant,entity,source,row.externalId(),identity.pin(),input.observedAt(),now,true,input.requestId()));
                PostgresSourceReviews.updateEntity(c,project(c,primary,PostgresSourceReviews.active(c,tenant,entity),current.version()+1));
                resolved.add(new SourceSnapshot.Resolved(row.externalId(),entity,identity.pin(),reviewId,current.version()+1));
            }
            int absent=0;
            if(input.complete())for(var p:previous.values())if(!inputs.containsKey(p.externalId())){
                write(c,new SourceSnapshot.Presence(tenant,p.entityId(),source,p.externalId(),p.identity(),input.observedAt(),now,false,input.requestId()));
                var current=PostgresSourceReviews.entity(c,tenant,p.entityId());var primary=Objects.requireNonNull(PostgresSourceReviews.primary(c,tenant,p.entityId()));
                PostgresSourceReviews.updateEntity(c,project(c,primary,PostgresSourceReviews.active(c,tenant,p.entityId()),current.version()+1));absent++;
            }
            var result=new SourceSnapshot.Receipt(tenant,actor,source,namespace,input,now,digest,resolved,absent);
            try(var s=c.prepareStatement("INSERT INTO inventory.source_snapshot(tenant_id,source_instance_id,request_id,actor,namespace,observed_at,observed_epoch_nanos,body) VALUES(?,?,?,?,?,?,?,?::jsonb)")){
                bind(s,tenant,source);s.setObject(3,input.requestId());s.setString(4,actor);s.setString(5,namespace);s.setTimestamp(6,Timestamp.from(input.observedAt()));s.setBigDecimal(7,PostgresInventoryStore.nanos(input.observedAt()));s.setString(8,SourceReviewJson.JSON.writeValueAsString(SourceSnapshotJson.wire(result)));s.executeUpdate();}
            lease.require(lease.token(),PostgresSourceScans.now(c));if(Thread.currentThread().isInterrupted())throw new SourceScan.Failure(SourceScan.Code.DEADLINE);
            PostgresSourceScans.write(c,lease.release());
        return result;
    }
    @Override public SourceBindingCorrection.Receipt correct(TenantId tenant,String actor,String source,String namespace,SourceBindingCorrection.Command command,String digest){
        var scope=new SourceScan.Scope(tenant,source,"cmdb-host");AssetIdentity.namespace(namespace);SourceReview.bounded(actor,128);
        SourceBindingCorrection.Receipt[] result={null};
        Transactions.run(dataSource,c->{
            PostgresSourceScans.lock(c,scope);var started=PostgresSourceScans.now(c);
            var prior=readCorrection(c,tenant,source,command.requestId());
            if(prior!=null){if(!prior.actor().equals(actor) || !prior.namespace().equals(namespace) || !prior.command().equals(command) || !prior.snapshot().mappingDigest().equals(digest))throw new SourceBindingCorrection.Conflict(SourceBindingCorrection.Code.CORRECTION_REQUEST_CONFLICT);result[0]=prior;return;}
            if(read(c,tenant,source,command.requestId())!=null)throw new SourceBindingCorrection.Conflict(SourceBindingCorrection.Code.CORRECTION_REQUEST_CONFLICT);
            command.input().requireFresh(started);
            try(var s=c.prepareStatement("SELECT count(*) FROM inventory.source_binding_correction WHERE tenant_id=? AND source_instance_id=?")){
                bind(s,tenant,source);try(var r=s.executeQuery()){r.next();if(r.getLong(1)>=SourceBindingCorrection.MAX_RECEIPTS)throw new SourceBindingCorrection.Conflict(SourceBindingCorrection.Code.CORRECTION_LIMIT);}}
            var targets=new TreeSet<EntityId>(Comparator.comparing(v->v.value().toString()));targets.add(command.previousEntityId());targets.add(command.targetEntityId());
            for(var id:targets)PostgresSourceReviews.lock(c,tenant,id);
            try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){
                s.setString(1,"source-review-binding:"+tenant.value().length()+":"+tenant.value()+":"+source.length()+":"+source+":"+command.externalId());s.setQueryTimeout(5);s.execute();}
            SourceSnapshot.Presence before;
            try(var s=c.prepareStatement("SELECT body FROM inventory.entity_source_presence WHERE tenant_id=? AND source_instance_id=? AND external_id=?")){
                bind(s,tenant,source);s.setString(3,command.externalId());try(var r=s.executeQuery()){if(!r.next())throw new SourceBindingCorrection.Conflict(SourceBindingCorrection.Code.BINDING_CHANGED);before=SourceSnapshotJson.presence(r.getString(1));}}
            var previous=PostgresSourceReviews.entity(c,tenant,command.previousEntityId());var target=PostgresSourceReviews.entity(c,tenant,command.targetEntityId());
            command.requireCurrent(before,namespace,previous.version(),target.version());
            PostgresAssetIdentities.validatePin(c,tenant,target.id(),command.targetIdentity());
            var active=PostgresSourceReviews.active(c,tenant,previous.id());
            if(active!=null && active.source().sourceInstanceId().equals(source) && active.source().externalId().equals(command.externalId()))throw new SourceBindingCorrection.Conflict(SourceBindingCorrection.Code.BINDING_FIELDS_ACTIVE);
            // Only the current projection is replaced. The prior value is retained in the atomic correction receipt.
            try(var s=c.prepareStatement("DELETE FROM inventory.entity_source_presence WHERE tenant_id=? AND source_instance_id=? AND external_id=?")){
                bind(s,tenant,source);s.setString(3,command.externalId());if(s.executeUpdate()!=1)throw new SourceBindingCorrection.Conflict(SourceBindingCorrection.Code.BINDING_CHANGED);}
            if(!previous.id().equals(target.id()))PostgresSourceReviews.updateEntity(c,project(c,Objects.requireNonNull(PostgresSourceReviews.primary(c,tenant,previous.id())),active,previous.version()+1));
            var snapshot=ingestInside(c,tenant,actor,source,namespace,command.input(),digest);
            result[0]=new SourceBindingCorrection.Receipt(tenant,actor,source,namespace,command,before,snapshot,previous.version()+1);
            try(var s=c.prepareStatement("INSERT INTO inventory.source_binding_correction(tenant_id,source_instance_id,request_id,namespace,actor,previous_entity_id,target_entity_id,body) VALUES(?,?,?,?,?,?,?,?::jsonb)")){
                bind(s,tenant,source);s.setObject(3,command.requestId());s.setString(4,namespace);s.setString(5,actor);s.setObject(6,previous.id().value());s.setObject(7,target.id().value());s.setString(8,SourceReviewJson.JSON.writeValueAsString(SourceBindingCorrectionJson.wire(result[0])));s.executeUpdate();}
            if(!PostgresSourceScans.now(c).isBefore(started.plusSeconds(30)) || Thread.currentThread().isInterrupted())throw new SourceScan.Failure(SourceScan.Code.DEADLINE);
        });return result[0];
    }
    private static SourceBindingCorrection.Receipt readCorrection(Connection c,TenantId tenant,String source,UUID id)throws SQLException{
        try(var s=c.prepareStatement("SELECT body FROM inventory.source_binding_correction WHERE tenant_id=? AND source_instance_id=? AND request_id=?")){
            bind(s,tenant,source);s.setObject(3,id);try(var r=s.executeQuery()){return r.next()?SourceBindingCorrectionJson.receipt(r.getString(1)):null;}}
    }
    @Override public Optional<SourceBindingCorrection.Receipt> correction(TenantId tenant,String actor,String source,String namespace,UUID id){
        try(var c=dataSource.getConnection()){var r=readCorrection(c,tenant,source,id);return r!=null && r.actor().equals(actor) && r.namespace().equals(namespace)?Optional.of(r):Optional.empty();}catch(SQLException e){throw new IllegalStateException("Correction receipt unavailable");}
    }
    @Override public List<SourceBindingCorrection.Receipt> corrections(TenantId tenant,String source,String namespace,EntityId entity,UUID after,int limit){
        if(limit<1 || limit>25)throw new IllegalArgumentException("Invalid correction page limit");AssetIdentity.namespace(namespace);
        try(var c=dataSource.getConnection();var s=c.prepareStatement("SELECT body FROM inventory.source_binding_correction WHERE tenant_id=? AND source_instance_id=? AND namespace=? AND (previous_entity_id=? OR target_entity_id=?) AND (?::uuid IS NULL OR request_id>?) ORDER BY request_id LIMIT ?")){
            bind(s,tenant,source);s.setString(3,namespace);s.setObject(4,entity.value());s.setObject(5,entity.value());s.setObject(6,after);s.setObject(7,after);s.setInt(8,limit+1);
            var items=new ArrayList<SourceBindingCorrection.Receipt>();try(var r=s.executeQuery()){while(r.next())items.add(SourceBindingCorrectionJson.receipt(r.getString(1)));}return List.copyOf(items);
        }catch(SQLException e){throw new IllegalStateException("Correction history unavailable");}
    }
    private static void write(Connection c,SourceSnapshot.Presence p)throws SQLException{
        try(var s=c.prepareStatement("INSERT INTO inventory.entity_source_presence(tenant_id,source_instance_id,external_id,entity_id,identity_id,expires_epoch_nanos,present,body) VALUES(?,?,?,?,?,?,?,?::jsonb) ON CONFLICT(tenant_id,source_instance_id,external_id) DO UPDATE SET expires_epoch_nanos=EXCLUDED.expires_epoch_nanos,present=EXCLUDED.present,body=EXCLUDED.body")){
            bind(s,p.tenantId(),p.sourceInstanceId());s.setString(3,p.externalId());s.setObject(4,p.entityId().value());s.setObject(5,p.identity().id());s.setBigDecimal(6,PostgresInventoryStore.nanos(p.expiresAt()));s.setBoolean(7,p.present());s.setString(8,SourceReviewJson.JSON.writeValueAsString(SourceSnapshotJson.wire(p)));s.executeUpdate();}
    }
    private static SourceSnapshot.Receipt read(Connection c,TenantId tenant,String source,UUID id)throws SQLException{
        try(var s=c.prepareStatement("SELECT body FROM inventory.source_snapshot WHERE tenant_id=? AND source_instance_id=? AND request_id=?")){bind(s,tenant,source);s.setObject(3,id);try(var r=s.executeQuery()){return r.next()?SourceSnapshotJson.receipt(r.getString(1)):null;}}
    }
    @Override public Optional<SourceSnapshot.Receipt> receipt(TenantId tenant,String actor,String source,String namespace,UUID id){try(var c=dataSource.getConnection()){var r=read(c,tenant,source,id);return r!=null && r.actor().equals(actor) && r.namespace().equals(namespace)?Optional.of(r):Optional.empty();}catch(SQLException e){throw new IllegalStateException("Snapshot receipt unavailable");}}
    @Override public Page presence(TenantId tenant,EntityId entity,String source){
        try(var c=dataSource.getConnection();var s=c.prepareStatement("SELECT p.body,i.active FROM inventory.entity_source_presence p JOIN inventory.asset_identity i ON i.tenant_id=p.tenant_id AND i.id=p.identity_id WHERE p.tenant_id=? AND p.entity_id=? AND p.source_instance_id=?")){
            c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setReadOnly(true);
            s.setString(1,tenant.value());s.setObject(2,entity.value());s.setString(3,source);s.setQueryTimeout(5);var now=PostgresSourceScans.now(c);var items=new ArrayList<State>();try(var r=s.executeQuery()){while(r.next())items.add(new State(SourceSnapshotJson.presence(r.getString(1)),r.getBoolean(2)));}c.commit();return new Page(now,items);
        }catch(SQLException e){throw new IllegalStateException("Source presence unavailable");}
    }
    private static void bind(PreparedStatement s,TenantId tenant,String source)throws SQLException{s.setString(1,tenant.value());s.setString(2,source);s.setQueryTimeout(5);}
}

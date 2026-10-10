package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.inventory.api.RelationStore;
import com.acme.opsweave.inventory.domain.EntityRelation;
import com.acme.opsweave.inventory.domain.RelationCardinality;
import com.acme.opsweave.inventory.domain.RelationConfirmation;
import com.acme.opsweave.inventory.domain.RelationCorrection;
import com.acme.opsweave.sharedkernel.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

final class PostgresRelationStore implements RelationStore {
    private static final String COLUMNS="id,from_entity_id,relation_type,relation_revision,to_entity_id,valid_from,valid_to,source_ref,data_mode,close_reason,version";
    private static final String CONFIRM_COLUMNS="relation_id,source_kind,source_key,batch_ref,data_mode,confirmed_at,withdrawn_at,withdraw_reason";
    private final DataSource source;
    PostgresRelationStore(DataSource source) { this.source=source; }
    @Override public WriteResult write(TenantId tenant, UUID requestId, EntityRelation relation, long expectedFromVersion, long expectedToVersion, RelationCardinality cardinality) {
        if (!tenant.equals(relation.tenantId())) throw new Conflict("Relation tenant mismatch");
        try (var c=source.getConnection()) { c.setAutoCommit(false); try {
            try (var s=c.prepareStatement("SELECT "+COLUMNS+" FROM inventory.entity_relation WHERE tenant_id=? AND request_id=? FOR UPDATE")) {
                s.setString(1,tenant.value());s.setObject(2,requestId);try(var r=s.executeQuery()){if(r.next()){var previous=row(tenant,r);if(!same(previous,relation))throw new Conflict("Relation request reused");c.commit();return new WriteResult(previous,true);}}}
            try (var s=c.prepareStatement("SELECT id,version FROM inventory.entity WHERE tenant_id=? AND id IN (?,?) FOR UPDATE")) {
                s.setString(1,tenant.value());s.setObject(2,relation.fromEntityId().value());s.setObject(3,relation.toEntityId().value());
                var versions=new HashMap<UUID,Long>();try(var r=s.executeQuery()){while(r.next())versions.put(r.getObject("id",UUID.class),r.getLong("version"));}
                if(!Objects.equals(versions.get(relation.fromEntityId().value()),expectedFromVersion)||!Objects.equals(versions.get(relation.toEntityId().value()),expectedToVersion))throw new Conflict("Entity version changed");
            }
            if(cardinality.maxActiveFrom()>0&&openEnded(c,tenant,relation.relationType(),relation.fromEntityId(),true)>=cardinality.maxActiveFrom())throw new Conflict("Relation from endpoint cardinality exceeded");
            if(cardinality.maxActiveTo()>0&&openEnded(c,tenant,relation.relationType(),relation.toEntityId(),false)>=cardinality.maxActiveTo())throw new Conflict("Relation to endpoint cardinality exceeded");
            try (var s=c.prepareStatement("SELECT 1 FROM inventory.entity_relation WHERE tenant_id=? AND relation_type=? AND from_entity_id=? AND to_entity_id=? LIMIT 1")) {
                s.setString(1,tenant.value());s.setString(2,relation.relationType());s.setObject(3,relation.fromEntityId().value());s.setObject(4,relation.toEntityId().value());
                try(var r=s.executeQuery()){if(r.next())throw new Conflict("Relation already recorded");}
            }
            try (var s=c.prepareStatement("INSERT INTO inventory.entity_relation(tenant_id,id,request_id,from_entity_id,to_entity_id,relation_type,relation_revision,valid_from,valid_to,source_ref,data_mode) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                s.setString(1,tenant.value());s.setObject(2,relation.id());s.setObject(3,requestId);s.setObject(4,relation.fromEntityId().value());s.setObject(5,relation.toEntityId().value());s.setString(6,relation.relationType());s.setInt(7,relation.relationRevision());s.setTimestamp(8,Timestamp.from(relation.validFrom()));if(relation.validTo()==null)s.setNull(9,Types.TIMESTAMP_WITH_TIMEZONE);else s.setTimestamp(9,Timestamp.from(relation.validTo()));s.setString(10,relation.sourceRef());s.setString(11,relation.dataMode());s.executeUpdate();
            }
            c.commit();return new WriteResult(relation,false);
        } catch(RuntimeException|SQLException failed){try{c.rollback();}catch(SQLException ignored){} if(failed instanceof RelationStore.Conflict conflict)throw conflict;if(failed instanceof SQLException sql)throw new IllegalStateException("Relation write unavailable",sql);throw new IllegalStateException("Relation write unavailable");}}
        catch(SQLException failed){throw new IllegalStateException("Relation write unavailable", failed);}
    }
    @Override public WriteResult close(TenantId tenant, UUID requestId, UUID relationId, Instant validTo, String reason, long expectedVersion) {
        try (var c=source.getConnection()) { c.setAutoCommit(false); try {
            try (var s=c.prepareStatement("SELECT "+COLUMNS+" FROM inventory.entity_relation WHERE tenant_id=? AND close_request_id=? FOR UPDATE")) {
                s.setString(1,tenant.value());s.setObject(2,requestId);try(var r=s.executeQuery()){if(r.next()){
                    var previous=row(tenant,r);
                    if(!previous.id().equals(relationId)||!Objects.equals(previous.validTo(),validTo)||!Objects.equals(previous.closeReason(),reason)||previous.version()-1!=expectedVersion)throw new Conflict("Relation close request reused");
                    c.commit();return new WriteResult(previous,true);}}}
            EntityRelation current;
            try (var s=c.prepareStatement("SELECT "+COLUMNS+" FROM inventory.entity_relation WHERE tenant_id=? AND id=? FOR UPDATE")) {
                s.setString(1,tenant.value());s.setObject(2,relationId);try(var r=s.executeQuery()){
                    if(!r.next())throw new NotFound("Relation missing");
                    current=row(tenant,r);
                }}
            if(current.validTo()!=null)throw new Conflict("Relation already closed");
            if(current.version()!=expectedVersion)throw new Conflict("Relation version changed");
            try (var s=c.prepareStatement("UPDATE inventory.entity_relation SET valid_to=?,close_reason=?,close_request_id=?,version=version+1 WHERE tenant_id=? AND id=?")) {
                s.setTimestamp(1,Timestamp.from(validTo));s.setString(2,reason);s.setObject(3,requestId);s.setString(4,tenant.value());s.setObject(5,relationId);s.executeUpdate();
            }
            var closed=new EntityRelation(current.id(),current.tenantId(),current.fromEntityId(),current.relationType(),current.relationRevision(),current.toEntityId(),current.validFrom(),validTo,current.sourceRef(),current.dataMode(),current.version()+1,reason);
            c.commit();return new WriteResult(closed,false);
        } catch(RuntimeException|SQLException failed){try{c.rollback();}catch(SQLException ignored){} if(failed instanceof RelationStore.Conflict||failed instanceof RelationStore.NotFound)throw (RuntimeException)failed;if(failed instanceof SQLException sql)throw new IllegalStateException("Relation write unavailable",sql);throw new IllegalStateException("Relation write unavailable");}}
        catch(SQLException failed){throw new IllegalStateException("Relation write unavailable", failed);}
    }
    @Override public WriteResult correct(TenantId tenant, UUID requestId, UUID previousRelationId, String reason, long expectedVersion, EntityRelation replacement, RelationCardinality cardinality) {
        if (!tenant.equals(replacement.tenantId())) throw new CorrectionConflict("Relation tenant mismatch");
        try (var c=source.getConnection()) { c.setAutoCommit(false); try {
            try (var s=c.prepareStatement("SELECT relation_id,corrected_relation_id,reason,expected_version FROM inventory.entity_relation_correction WHERE tenant_id=? AND request_id=? FOR UPDATE")) {
                s.setString(1,tenant.value());s.setObject(2,requestId);try(var r=s.executeQuery()){if(r.next()){
                    if(!r.getObject("relation_id",UUID.class).equals(previousRelationId)||!r.getString("reason").equals(reason)||r.getLong("expected_version")!=expectedVersion)throw new CorrectionConflict("Relation correction request reused");
                    var recordedId=r.getObject("corrected_relation_id",UUID.class);
                    EntityRelation recorded=null;
                    try (var s2=c.prepareStatement("SELECT "+COLUMNS+" FROM inventory.entity_relation WHERE tenant_id=? AND id=?")) {
                        s2.setString(1,tenant.value());s2.setObject(2,recordedId);
                        try(var r2=s2.executeQuery()){if(r2.next())recorded=row(tenant,r2);}
                    }
                    if(recorded==null||!sameCorrectionValue(recorded,replacement))throw new CorrectionConflict("Relation correction request reused");
                    c.commit();return new WriteResult(recorded,true);
                }}}
            EntityRelation current;
            try (var s=c.prepareStatement("SELECT "+COLUMNS+" FROM inventory.entity_relation WHERE tenant_id=? AND id=? FOR UPDATE")) {
                s.setString(1,tenant.value());s.setObject(2,previousRelationId);try(var r=s.executeQuery()){
                    if(!r.next())throw new NotFound("Relation missing");
                    current=row(tenant,r);
                }}
            if(current.validTo()!=null)throw new CorrectionConflict("Relation already closed");
            if(current.version()!=expectedVersion)throw new CorrectionConflict("Relation version changed");
            if(!replacement.validFrom().isAfter(current.validFrom()))throw new CorrectionConflict("Relation closes before it starts");
            try (var s=c.prepareStatement("SELECT 1 FROM inventory.entity WHERE tenant_id=? AND id IN (?,?) FOR UPDATE")) {
                s.setString(1,tenant.value());s.setObject(2,replacement.fromEntityId().value());s.setObject(3,replacement.toEntityId().value());
                try(var r=s.executeQuery()){if(!r.next())throw new CorrectionConflict("Relation endpoint missing");}
            }
            if(cardinality.maxActiveFrom()>0&&openEndedExcluding(c,tenant,replacement.relationType(),replacement.fromEntityId(),true,previousRelationId)>=cardinality.maxActiveFrom())throw new CorrectionConflict("Relation from endpoint cardinality exceeded");
            if(cardinality.maxActiveTo()>0&&openEndedExcluding(c,tenant,replacement.relationType(),replacement.toEntityId(),false,previousRelationId)>=cardinality.maxActiveTo())throw new CorrectionConflict("Relation to endpoint cardinality exceeded");
            try (var s=c.prepareStatement("SELECT 1 FROM inventory.entity_relation WHERE tenant_id=? AND relation_type=? AND from_entity_id=? AND to_entity_id=? LIMIT 1")) {
                s.setString(1,tenant.value());s.setString(2,replacement.relationType());s.setObject(3,replacement.fromEntityId().value());s.setObject(4,replacement.toEntityId().value());
                try(var r=s.executeQuery()){if(r.next())throw new CorrectionConflict("Relation already recorded");}
            }
            try (var s=c.prepareStatement("UPDATE inventory.entity_relation SET valid_to=?,close_reason=?,version=version+1 WHERE tenant_id=? AND id=?")) {
                s.setTimestamp(1,Timestamp.from(replacement.validFrom()));s.setString(2,reason);s.setString(3,tenant.value());s.setObject(4,previousRelationId);s.executeUpdate();
            }
            try (var s=c.prepareStatement("INSERT INTO inventory.entity_relation(tenant_id,id,request_id,from_entity_id,to_entity_id,relation_type,relation_revision,valid_from,valid_to,source_ref,data_mode) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                s.setString(1,tenant.value());s.setObject(2,replacement.id());s.setObject(3,replacement.id());s.setObject(4,replacement.fromEntityId().value());s.setObject(5,replacement.toEntityId().value());s.setString(6,replacement.relationType());s.setInt(7,replacement.relationRevision());s.setTimestamp(8,Timestamp.from(replacement.validFrom()));s.setNull(9,Types.TIMESTAMP_WITH_TIMEZONE);s.setString(10,replacement.sourceRef());s.setString(11,replacement.dataMode());s.executeUpdate();
            }
            try (var s=c.prepareStatement("INSERT INTO inventory.entity_relation_correction(tenant_id,request_id,relation_id,corrected_relation_id,reason,expected_version,corrected_at) VALUES(?,?,?,?,?,?,?)")) {
                s.setString(1,tenant.value());s.setObject(2,requestId);s.setObject(3,previousRelationId);s.setObject(4,replacement.id());s.setString(5,reason);s.setLong(6,expectedVersion);s.setTimestamp(7,Timestamp.from(replacement.validFrom()));s.executeUpdate();
            }
            c.commit();return new WriteResult(replacement,false);
        } catch(RuntimeException|SQLException failed){try{c.rollback();}catch(SQLException ignored){} if(known(failed))throw (RuntimeException)failed;if(failed instanceof SQLException sql)throw new IllegalStateException("Relation write unavailable",sql);throw new IllegalStateException("Relation write unavailable");}}
        catch(SQLException failed){throw new IllegalStateException("Relation write unavailable", failed);}
    }
    @Override public ConfirmationResult confirm(TenantId tenant, UUID requestId, UUID relationId, RelationConfirmation entry) {
        try (var c=source.getConnection()) { c.setAutoCommit(false); try {
            EntityRelation current;
            try (var s=c.prepareStatement("SELECT "+COLUMNS+" FROM inventory.entity_relation WHERE tenant_id=? AND id=? FOR UPDATE")) {
                s.setString(1,tenant.value());s.setObject(2,relationId);try(var r=s.executeQuery()){
                    if(!r.next())throw new NotFound("Relation missing");
                    current=row(tenant,r);
                }}
            try (var s=c.prepareStatement("SELECT "+CONFIRM_COLUMNS+" FROM inventory.entity_relation_confirmation WHERE tenant_id=? AND confirm_request_id=?")) {
                s.setString(1,tenant.value());s.setObject(2,requestId);try(var r=s.executeQuery()){if(r.next()){
                    var previous=confirmation(r);
                    if(!matches(r,relationId,entry.key())||!previous.dataMode().equals(entry.dataMode()))throw new ConfirmationConflict("Relation confirmation request reused");
                    c.commit();return new ConfirmationResult(previous,true);
                }}}
            var existing=loadConfirmation(c,tenant,relationId,entry.key());
            if(existing.isPresent()){
                var previous=existing.get();
                if(previous.withdrawn())throw new ConfirmationConflict("Relation confirmation already withdrawn");
                if(!previous.dataMode().equals(entry.dataMode()))throw new ConfirmationConflict("Relation confirmation conflicts");
                c.commit();return new ConfirmationResult(previous,true);
            }
            if(current.validTo()!=null)throw new ConfirmationConflict("Relation already closed");
            if(countConfirmations(c,tenant,relationId)>=RelationConfirmation.MAX_ENTRIES_PER_RELATION)throw new ConfirmationConflict("Relation confirmation capacity reached");
            try (var s=c.prepareStatement("INSERT INTO inventory.entity_relation_confirmation(tenant_id,relation_id,source_kind,source_key,batch_ref,data_mode,confirmed_at,confirm_request_id) VALUES(?,?,?,?,?,?,?,?)")) {
                s.setString(1,tenant.value());s.setObject(2,relationId);s.setString(3,entry.key().sourceKind());s.setString(4,entry.key().sourceKey());s.setString(5,entry.key().batchRef());s.setString(6,entry.dataMode());s.setTimestamp(7,Timestamp.from(entry.confirmedAt()));s.setObject(8,requestId);s.executeUpdate();
            }
            c.commit();return new ConfirmationResult(entry,false);
        } catch(RuntimeException|SQLException failed){try{c.rollback();}catch(SQLException ignored){} if(known(failed))throw (RuntimeException)failed;if(failed instanceof SQLException sql)throw new IllegalStateException("Relation write unavailable",sql);throw new IllegalStateException("Relation write unavailable");}}
        catch(SQLException failed){throw new IllegalStateException("Relation write unavailable", failed);}
    }
    @Override public ConfirmationResult withdraw(TenantId tenant, UUID requestId, UUID relationId, RelationConfirmation.Key key, Instant at, String reason) {
        try (var c=source.getConnection()) { c.setAutoCommit(false); try {
            EntityRelation current;
            try (var s=c.prepareStatement("SELECT "+COLUMNS+" FROM inventory.entity_relation WHERE tenant_id=? AND id=? FOR UPDATE")) {
                s.setString(1,tenant.value());s.setObject(2,relationId);try(var r=s.executeQuery()){
                    if(!r.next())throw new NotFound("Relation missing");
                    current=row(tenant,r);
                }}
            try (var s=c.prepareStatement("SELECT "+CONFIRM_COLUMNS+" FROM inventory.entity_relation_confirmation WHERE tenant_id=? AND withdraw_request_id=?")) {
                s.setString(1,tenant.value());s.setObject(2,requestId);try(var r=s.executeQuery()){if(r.next()){
                    if(!matches(r,relationId,key)||!reason.equals(r.getString("withdraw_reason")))throw new ConfirmationConflict("Relation confirmation withdrawal request reused");
                    c.commit();return new ConfirmationResult(confirmation(r),true);
                }}}
            var existing=loadConfirmation(c,tenant,relationId,key);
            if(existing.isEmpty())throw new NotFound("Relation confirmation missing");
            if(existing.get().withdrawn()){c.commit();return new ConfirmationResult(existing.get(),true);}
            long remaining;
            try (var s=c.prepareStatement("SELECT COUNT(*) FROM inventory.entity_relation_confirmation WHERE tenant_id=? AND relation_id=? AND withdrawn_at IS NULL AND NOT (source_kind=? AND source_key=? AND batch_ref=?)")) {
                s.setString(1,tenant.value());s.setObject(2,relationId);s.setString(3,key.sourceKind());s.setString(4,key.sourceKey());s.setString(5,key.batchRef());
                try(var r=s.executeQuery()){r.next();remaining=r.getLong(1);}
            }
            boolean deriveClose=remaining==0&&current.validTo()==null;
            if(deriveClose&&!at.isAfter(current.validFrom()))throw new ConfirmationConflict("Relation closes before it starts");
            var withdrawn=existing.get().withdraw(at,reason);
            try (var s=c.prepareStatement("UPDATE inventory.entity_relation_confirmation SET withdrawn_at=?,withdraw_request_id=?,withdraw_reason=? WHERE tenant_id=? AND relation_id=? AND source_kind=? AND source_key=? AND batch_ref=?")) {
                s.setTimestamp(1,Timestamp.from(at));s.setObject(2,requestId);s.setString(3,reason);s.setString(4,tenant.value());s.setObject(5,relationId);s.setString(6,key.sourceKind());s.setString(7,key.sourceKey());s.setString(8,key.batchRef());s.executeUpdate();
            }
            if(deriveClose){
                try (var s=c.prepareStatement("UPDATE inventory.entity_relation SET valid_to=?,close_reason=?,version=version+1 WHERE tenant_id=? AND id=?")) {
                    s.setTimestamp(1,Timestamp.from(at));s.setString(2,reason);s.setString(3,tenant.value());s.setObject(4,relationId);s.executeUpdate();
                }
            }
            c.commit();return new ConfirmationResult(withdrawn,false);
        } catch(RuntimeException|SQLException failed){try{c.rollback();}catch(SQLException ignored){} if(known(failed))throw (RuntimeException)failed;if(failed instanceof SQLException sql)throw new IllegalStateException("Relation write unavailable",sql);throw new IllegalStateException("Relation write unavailable");}}
        catch(SQLException failed){throw new IllegalStateException("Relation write unavailable", failed);}
    }
    @Override public Optional<EntityRelation> find(TenantId tenant,UUID relationId){
        try(var c=source.getConnection();var s=c.prepareStatement("SELECT "+COLUMNS+" FROM inventory.entity_relation WHERE tenant_id=? AND id=?")){
            s.setString(1,tenant.value());s.setObject(2,relationId);try(var r=s.executeQuery()){return r.next()?Optional.of(row(tenant,r)):Optional.empty();}
        }catch(SQLException failed){throw new IllegalStateException("Relation read unavailable", failed);}}
    @Override public List<EntityRelation> page(TenantId tenant,EntityId endpoint,UUID after,Instant asOf,int limit){if(limit<1||limit>50)throw new IllegalArgumentException("Invalid relation limit");
        String sql="SELECT "+COLUMNS+" FROM inventory.entity_relation WHERE tenant_id=? AND (from_entity_id=? OR to_entity_id=?) AND valid_from<=? AND (valid_to IS NULL OR valid_to>?)"+(after==null?"":" AND id>?")+" ORDER BY id LIMIT ?";
        try(var c=source.getConnection();var s=c.prepareStatement(sql)){
            s.setString(1,tenant.value());s.setObject(2,endpoint.value());s.setObject(3,endpoint.value());s.setTimestamp(4,Timestamp.from(asOf));s.setTimestamp(5,Timestamp.from(asOf));
            int next=6;if(after!=null)s.setObject(next++,after);s.setInt(next,limit+1);
            try(var r=s.executeQuery()){var out=new ArrayList<EntityRelation>();while(r.next())out.add(row(tenant,r));return List.copyOf(out);}
        }catch(SQLException failed){throw new IllegalStateException("Relation read unavailable", failed);}}
    @Override public List<EntityRelation> history(TenantId tenant,EntityId endpoint,UUID after,int limit){if(limit<1||limit>50)throw new IllegalArgumentException("Invalid relation limit");
        String sql="SELECT "+COLUMNS+" FROM inventory.entity_relation WHERE tenant_id=? AND (from_entity_id=? OR to_entity_id=?)"+(after==null?"":" AND id>?")+" ORDER BY id LIMIT ?";
        try(var c=source.getConnection();var s=c.prepareStatement(sql)){
            s.setString(1,tenant.value());s.setObject(2,endpoint.value());s.setObject(3,endpoint.value());
            int next=4;if(after!=null)s.setObject(next++,after);s.setInt(next,limit+1);
            try(var r=s.executeQuery()){var out=new ArrayList<EntityRelation>();while(r.next())out.add(row(tenant,r));return List.copyOf(out);}
        }catch(SQLException failed){throw new IllegalStateException("Relation read unavailable", failed);}}
    @Override public List<RelationConfirmation> confirmations(TenantId tenant,UUID relationId){
        String sql="SELECT "+CONFIRM_COLUMNS+" FROM inventory.entity_relation_confirmation WHERE tenant_id=? AND relation_id=? ORDER BY source_kind,source_key,batch_ref LIMIT "+(RelationConfirmation.MAX_ENTRIES_PER_RELATION+1);
        try(var c=source.getConnection();var s=c.prepareStatement(sql)){
            s.setString(1,tenant.value());s.setObject(2,relationId);
            try(var r=s.executeQuery()){var out=new ArrayList<RelationConfirmation>();while(r.next())out.add(confirmation(r));return List.copyOf(out);}
        }catch(SQLException failed){throw new IllegalStateException("Relation read unavailable", failed);}}
    @Override public List<RelationCorrection> corrections(TenantId tenant,EntityId endpoint,UUID after,int limit){if(limit<1||limit>50)throw new IllegalArgumentException("Invalid relation limit");
        String sql="SELECT request_id,relation_id,corrected_relation_id,reason,expected_version,corrected_at FROM inventory.entity_relation_correction c WHERE c.tenant_id=?"
            +" AND (EXISTS (SELECT 1 FROM inventory.entity_relation r WHERE r.tenant_id=c.tenant_id AND r.id=c.relation_id AND (r.from_entity_id=? OR r.to_entity_id=?))"
            +" OR EXISTS (SELECT 1 FROM inventory.entity_relation r WHERE r.tenant_id=c.tenant_id AND r.id=c.corrected_relation_id AND (r.from_entity_id=? OR r.to_entity_id=?)))"
            +(after==null?"":" AND c.request_id>?")+" ORDER BY c.request_id LIMIT ?";
        try(var c=source.getConnection();var s=c.prepareStatement(sql)){
            s.setString(1,tenant.value());s.setObject(2,endpoint.value());s.setObject(3,endpoint.value());s.setObject(4,endpoint.value());s.setObject(5,endpoint.value());
            int next=6;if(after!=null)s.setObject(next++,after);s.setInt(next,limit+1);
            try(var r=s.executeQuery()){var out=new ArrayList<RelationCorrection>();while(r.next())out.add(new RelationCorrection(r.getObject("request_id",UUID.class),r.getObject("relation_id",UUID.class),r.getObject("corrected_relation_id",UUID.class),r.getString("reason"),r.getLong("expected_version"),r.getTimestamp("corrected_at").toInstant()));return List.copyOf(out);}
        }catch(SQLException failed){throw new IllegalStateException("Relation read unavailable", failed);}}
    private static long openEnded(Connection c,TenantId tenant,String relationType,EntityId endpoint,boolean fromSide)throws SQLException{
        try(var s=c.prepareStatement("SELECT COUNT(*) FROM inventory.entity_relation WHERE tenant_id=? AND relation_type=? AND "+(fromSide?"from_entity_id":"to_entity_id")+"=? AND valid_to IS NULL")){
            s.setString(1,tenant.value());s.setString(2,relationType);s.setObject(3,endpoint.value());try(var r=s.executeQuery()){r.next();return r.getLong(1);}}}
    /** The corrected-away row terminates inside the same write and must not consume a cardinality slot. */
    private static long openEndedExcluding(Connection c,TenantId tenant,String relationType,EntityId endpoint,boolean fromSide,UUID excluded)throws SQLException{
        try(var s=c.prepareStatement("SELECT COUNT(*) FROM inventory.entity_relation WHERE tenant_id=? AND relation_type=? AND "+(fromSide?"from_entity_id":"to_entity_id")+"=? AND valid_to IS NULL AND id<>?")){
            s.setString(1,tenant.value());s.setString(2,relationType);s.setObject(3,endpoint.value());s.setObject(4,excluded);try(var r=s.executeQuery()){r.next();return r.getLong(1);}}}
    private static long countConfirmations(Connection c,TenantId tenant,UUID relationId)throws SQLException{
        try(var s=c.prepareStatement("SELECT COUNT(*) FROM inventory.entity_relation_confirmation WHERE tenant_id=? AND relation_id=?")){
            s.setString(1,tenant.value());s.setObject(2,relationId);try(var r=s.executeQuery()){r.next();return r.getLong(1);}}}
    private static Optional<RelationConfirmation> loadConfirmation(Connection c,TenantId tenant,UUID relationId,RelationConfirmation.Key key)throws SQLException{
        try(var s=c.prepareStatement("SELECT "+CONFIRM_COLUMNS+" FROM inventory.entity_relation_confirmation WHERE tenant_id=? AND relation_id=? AND source_kind=? AND source_key=? AND batch_ref=?")){
            s.setString(1,tenant.value());s.setObject(2,relationId);s.setString(3,key.sourceKind());s.setString(4,key.sourceKey());s.setString(5,key.batchRef());
            try(var r=s.executeQuery()){return r.next()?Optional.of(confirmation(r)):Optional.empty();}}}
    private static boolean matches(ResultSet r,UUID relationId,RelationConfirmation.Key key)throws SQLException{
        return r.getObject("relation_id",UUID.class).equals(relationId)&&r.getString("source_kind").equals(key.sourceKind())
            &&r.getString("source_key").equals(key.sourceKey())&&r.getString("batch_ref").equals(key.batchRef());}
    private static RelationConfirmation confirmation(ResultSet r)throws SQLException{
        return new RelationConfirmation(r.getString("source_kind"),r.getString("source_key"),r.getString("batch_ref"),r.getString("data_mode"),
            r.getTimestamp("confirmed_at").toInstant(),r.getTimestamp("withdrawn_at")==null?null:r.getTimestamp("withdrawn_at").toInstant(),r.getString("withdraw_reason"));}
    private static boolean sameCorrectionValue(EntityRelation recorded,EntityRelation replacement){
        return recorded.fromEntityId().equals(replacement.fromEntityId())&&recorded.toEntityId().equals(replacement.toEntityId())
            &&recorded.relationType().equals(replacement.relationType())&&recorded.relationRevision()==replacement.relationRevision();}
    private static boolean known(Throwable failed){
        return failed instanceof RelationStore.Conflict||failed instanceof RelationStore.NotFound
            ||failed instanceof RelationStore.CorrectionConflict||failed instanceof RelationStore.ConfirmationConflict;
    }
    private static EntityRelation row(TenantId tenant,ResultSet r)throws SQLException{return new EntityRelation(r.getObject("id",UUID.class),tenant,new EntityId(r.getObject("from_entity_id",UUID.class)),r.getString("relation_type"),r.getInt("relation_revision"),new EntityId(r.getObject("to_entity_id",UUID.class)),r.getTimestamp("valid_from").toInstant(),r.getTimestamp("valid_to")==null?null:r.getTimestamp("valid_to").toInstant(),r.getString("source_ref"),r.getString("data_mode"),r.getLong("version"),r.getString("close_reason"));}
    private static boolean same(EntityRelation a,EntityRelation b){return a.tenantId().equals(b.tenantId())&&a.fromEntityId().equals(b.fromEntityId())&&a.relationType().equals(b.relationType())&&a.relationRevision()==b.relationRevision()&&a.toEntityId().equals(b.toEntityId())&&a.validFrom().equals(b.validFrom())&&(a.closeReason()!=null||Objects.equals(a.validTo(),b.validTo()))&&a.sourceRef().equals(b.sourceRef())&&a.dataMode().equals(b.dataMode());}
}

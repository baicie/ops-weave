package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.inventory.api.RelationStore;
import com.acme.opsweave.inventory.domain.EntityRelation;
import com.acme.opsweave.sharedkernel.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

final class PostgresRelationStore implements RelationStore {
    private final DataSource source;
    PostgresRelationStore(DataSource source) { this.source=source; }
    @Override public WriteResult write(TenantId tenant, UUID requestId, EntityRelation relation, long expectedFromVersion, long expectedToVersion) {
        if (!tenant.equals(relation.tenantId())) throw new Conflict("Relation tenant mismatch");
        try (var c=source.getConnection()) { c.setAutoCommit(false); try {
            try (var s=c.prepareStatement("SELECT id,from_entity_id,relation_type,relation_revision,to_entity_id,valid_from,valid_to,source_ref,data_mode FROM inventory.entity_relation WHERE tenant_id=? AND request_id=? FOR UPDATE")) {
                s.setString(1,tenant.value());s.setObject(2,requestId);try(var r=s.executeQuery()){if(r.next()){var previous=row(tenant,r);if(!same(previous,relation))throw new Conflict("Relation request reused");c.commit();return new WriteResult(previous,true);}}}
            try (var s=c.prepareStatement("SELECT id,version FROM inventory.entity WHERE tenant_id=? AND id IN (?,?) FOR UPDATE")) {
                s.setString(1,tenant.value());s.setObject(2,relation.fromEntityId().value());s.setObject(3,relation.toEntityId().value());
                var versions=new HashMap<UUID,Long>();try(var r=s.executeQuery()){while(r.next())versions.put(r.getObject("id",UUID.class),r.getLong("version"));}
                if(!Objects.equals(versions.get(relation.fromEntityId().value()),expectedFromVersion)||!Objects.equals(versions.get(relation.toEntityId().value()),expectedToVersion))throw new Conflict("Entity version changed");
            }
            try (var s=c.prepareStatement("INSERT INTO inventory.entity_relation(tenant_id,id,request_id,from_entity_id,to_entity_id,relation_type,relation_revision,valid_from,valid_to,source_ref,data_mode) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                s.setString(1,tenant.value());s.setObject(2,relation.id());s.setObject(3,requestId);s.setObject(4,relation.fromEntityId().value());s.setObject(5,relation.toEntityId().value());s.setString(6,relation.relationType());s.setInt(7,relation.relationRevision());s.setTimestamp(8,Timestamp.from(relation.validFrom()));if(relation.validTo()==null)s.setNull(9,Types.TIMESTAMP_WITH_TIMEZONE);else s.setTimestamp(9,Timestamp.from(relation.validTo()));s.setString(10,relation.sourceRef());s.setString(11,relation.dataMode());s.executeUpdate();
            }
            c.commit();return new WriteResult(relation,false);
        } catch(RuntimeException|SQLException failed){try{c.rollback();}catch(SQLException ignored){} if(failed instanceof RelationStore.Conflict conflict)throw conflict;throw new IllegalStateException("Relation write unavailable");}}
        catch(SQLException failed){throw new IllegalStateException("Relation write unavailable");}
    }
    @Override public List<EntityRelation> page(TenantId tenant,EntityId endpoint,UUID after,Instant asOf,int limit){if(limit<1||limit>50)throw new IllegalArgumentException("Invalid relation limit");
        try(var c=source.getConnection();var s=c.prepareStatement("SELECT id,from_entity_id,relation_type,relation_revision,to_entity_id,valid_from,valid_to,source_ref,data_mode FROM inventory.entity_relation WHERE tenant_id=? AND (from_entity_id=? OR to_entity_id=?) AND valid_from<=? AND (valid_to IS NULL OR valid_to>?) AND (? IS NULL OR id>?) ORDER BY id LIMIT ?")){
            s.setString(1,tenant.value());s.setObject(2,endpoint.value());s.setObject(3,endpoint.value());s.setTimestamp(4,Timestamp.from(asOf));s.setTimestamp(5,Timestamp.from(asOf));if(after==null)s.setNull(6,Types.OTHER);else s.setObject(6,after);if(after==null)s.setNull(7,Types.OTHER);else s.setObject(7,after);s.setInt(8,limit+1);try(var r=s.executeQuery()){var out=new ArrayList<EntityRelation>();while(r.next())out.add(row(tenant,r));return List.copyOf(out);}
        }catch(SQLException failed){throw new IllegalStateException("Relation read unavailable");}}
    private static EntityRelation row(TenantId tenant,ResultSet r)throws SQLException{return new EntityRelation(r.getObject("id",UUID.class),tenant,new EntityId(r.getObject("from_entity_id",UUID.class)),r.getString("relation_type"),r.getInt("relation_revision"),new EntityId(r.getObject("to_entity_id",UUID.class)),r.getTimestamp("valid_from").toInstant(),r.getTimestamp("valid_to")==null?null:r.getTimestamp("valid_to").toInstant(),r.getString("source_ref"),r.getString("data_mode"),1);}
    private static boolean same(EntityRelation a,EntityRelation b){return a.tenantId().equals(b.tenantId())&&a.fromEntityId().equals(b.fromEntityId())&&a.relationType().equals(b.relationType())&&a.relationRevision()==b.relationRevision()&&a.toEntityId().equals(b.toEntityId())&&a.validFrom().equals(b.validFrom())&&Objects.equals(a.validTo(),b.validTo())&&a.sourceRef().equals(b.sourceRef())&&a.dataMode().equals(b.dataMode());}
}

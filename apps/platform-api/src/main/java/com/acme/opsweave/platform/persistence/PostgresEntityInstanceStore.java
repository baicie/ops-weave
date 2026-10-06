package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.inventory.api.EntityInstanceStore;
import com.acme.opsweave.inventory.domain.Entity;
import com.acme.opsweave.sharedkernel.TenantId;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import tools.jackson.databind.json.JsonMapper;

final class PostgresEntityInstanceStore implements EntityInstanceStore {
    private final DataSource source; private final JsonMapper json=JsonMapper.builder().build();
    PostgresEntityInstanceStore(DataSource source){this.source=source;}
    @Override public WriteResult write(TenantId tenant,UUID requestId,Entity entity,Long expectedVersion){
        if(!tenant.equals(entity.tenantId()))throw new Conflict("Entity tenant mismatch");
        com.acme.opsweave.inventory.domain.EntityReadLimits.checkForWrite(entity.attributes());
        try(var c=source.getConnection()){c.setAutoCommit(false);try{
            try(var lock=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){
                lock.setQueryTimeout(5);lock.setString(1,"entity-instance:"+tenant.value().length()+":"+tenant.value()+":"+requestId);lock.execute();
            }
            String body=json.writeValueAsString(requestBody(entity,expectedVersion));
            try(var q=c.prepareStatement("SELECT body FROM inventory.entity_instance_request WHERE tenant_id=? AND request_id=? FOR UPDATE")){q.setString(1,tenant.value());q.setObject(2,requestId);try(var r=q.executeQuery()){if(r.next()){String prior=r.getString(1);if(!sameRequest(prior,body))throw new Conflict("Entity request reused");c.commit();return new WriteResult(decode(tenant,prior),true);}}}
            try(var q=c.prepareStatement("SELECT version FROM inventory.entity WHERE tenant_id=? AND id=? FOR UPDATE")){q.setString(1,tenant.value());q.setObject(2,entity.id().value());try(var r=q.executeQuery()){if(r.next()){if(expectedVersion==null||r.getLong(1)!=expectedVersion)throw new Conflict("Entity version changed");}else if(expectedVersion!=null)throw new Conflict("Entity version changed");}}
            String sql=expectedVersion==null?"INSERT INTO inventory.entity(tenant_id,id,entity_type,name,lifecycle,version,attributes,model_id,model_revision,model_digest,last_seen_at,last_seen_epoch_nanos) VALUES(?,?,?,?,?,1,?::jsonb,?,?,?,?,?)":"UPDATE inventory.entity SET entity_type=?,name=?,lifecycle=?,version=version+1,attributes=?::jsonb,model_id=?,model_revision=?,model_digest=?,last_seen_at=?,last_seen_epoch_nanos=? WHERE tenant_id=? AND id=? AND version=?";
            try(var s=c.prepareStatement(sql)){if(expectedVersion==null){s.setString(1,tenant.value());s.setObject(2,entity.id().value());s.setString(3,entity.entityType());s.setString(4,entity.name());s.setString(5,entity.lifecycle().name());s.setString(6,json.writeValueAsString(entity.attributes()));bindModel(s,7,entity.model());s.setTimestamp(10,Timestamp.from(entity.lastSeen()));s.setBigDecimal(11,PostgresInventoryStore.nanos(entity.lastSeen()));}else{s.setString(1,entity.entityType());s.setString(2,entity.name());s.setString(3,entity.lifecycle().name());s.setString(4,json.writeValueAsString(entity.attributes()));bindModel(s,5,entity.model());s.setTimestamp(8,Timestamp.from(entity.lastSeen()));s.setBigDecimal(9,PostgresInventoryStore.nanos(entity.lastSeen()));s.setString(10,tenant.value());s.setObject(11,entity.id().value());s.setLong(12,expectedVersion);}s.executeUpdate();}
            try(var s=c.prepareStatement("INSERT INTO inventory.entity_instance_request(tenant_id,request_id,entity_id,body,created_at) VALUES(?,?,?,?::jsonb,?)")){s.setString(1,tenant.value());s.setObject(2,requestId);s.setObject(3,entity.id().value());s.setString(4,body);s.setTimestamp(5,Timestamp.from(Instant.now()));s.executeUpdate();}
            c.commit();return new WriteResult(entity,false);
        }catch(RuntimeException|SQLException failed){try{c.rollback();}catch(SQLException ignored){}if(failed instanceof Conflict conflict)throw conflict;throw new IllegalStateException("Entity write unavailable");}}catch(SQLException failed){throw new IllegalStateException("Entity write unavailable");}}
    private static Map<String,Object> requestBody(Entity entity,Long expectedVersion) {
        var storedEntity=new LinkedHashMap<String,Object>();
        storedEntity.put("id",entity.id().value().toString());storedEntity.put("entityType",entity.entityType());storedEntity.put("name",entity.name());
        storedEntity.put("lifecycle",entity.lifecycle().name());storedEntity.put("version",entity.version());storedEntity.put("lastSeen",entity.lastSeen().toString());storedEntity.put("attributes",entity.attributes());
        Object model=null;if(entity.model()!=null){var pin=new LinkedHashMap<String,Object>();pin.put("id",entity.model().id());pin.put("revision",entity.model().revision());pin.put("digest",entity.model().digest());model=pin;}
        var command=new LinkedHashMap<String,Object>();command.put("entity",storedEntity);command.put("expectedVersion",expectedVersion);command.put("model",model);return command;
    }
    static void bindModel(PreparedStatement statement,int index,com.acme.opsweave.inventory.domain.EntityModelPin model)throws SQLException {
        if(model==null){statement.setNull(index,Types.VARCHAR);statement.setNull(index+1,Types.INTEGER);statement.setNull(index+2,Types.VARCHAR);return;}
        statement.setString(index,model.id());statement.setInt(index+1,model.revision());statement.setString(index+2,model.digest());
    }
    private boolean sameRequest(String left,String right) {
        try {
            var a=new LinkedHashMap<>(json.readValue(left,Map.class));
            var b=new LinkedHashMap<>(json.readValue(right,Map.class));
            var ae=(Map<String,Object>)a.get("entity");var be=(Map<String,Object>)b.get("entity");
            ae.remove("lastSeen");be.remove("lastSeen");
            return a.equals(b);
        } catch (RuntimeException invalid) { throw new Conflict("Entity request reused"); }
    }
    private Entity decode(TenantId tenant,String body) {
        try {
            var envelope=json.readValue(body,Map.class);var value=(Map<String,Object>)envelope.get("entity");
            @SuppressWarnings("unchecked") var attrs=(Map<String,Object>) value.get("attributes");
            @SuppressWarnings("unchecked") var pin=(Map<String,Object>)envelope.get("model");
            var model=pin==null?null:new com.acme.opsweave.inventory.domain.EntityModelPin(String.valueOf(pin.get("id")),((Number)pin.get("revision")).intValue(),String.valueOf(pin.get("digest")));
            return new Entity(new com.acme.opsweave.sharedkernel.EntityId(UUID.fromString(String.valueOf(value.get("id")))),tenant,
                String.valueOf(value.get("entityType")),String.valueOf(value.get("name")),
                com.acme.opsweave.inventory.domain.Lifecycle.valueOf(String.valueOf(value.get("lifecycle"))),
                ((Number)value.get("version")).longValue(),Instant.parse(String.valueOf(value.get("lastSeen"))),attrs,model);
        } catch (RuntimeException invalid) { throw new IllegalStateException("Entity receipt unavailable"); }
    }
}

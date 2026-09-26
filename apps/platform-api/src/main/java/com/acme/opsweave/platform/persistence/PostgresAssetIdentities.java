package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.inventory.api.AssetIdentityStore;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.platform.inventory.AssetIdentityJson;
import com.acme.opsweave.sharedkernel.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

final class PostgresAssetIdentities implements AssetIdentityStore {
    private final DataSource dataSource;
    PostgresAssetIdentities(DataSource dataSource) { this.dataSource=dataSource; }
    static void validatePin(Connection c, TenantId tenant, EntityId entity, AssetIdentity.Pin pin) throws SQLException {
        if(pin == null) return;
        try(var s=c.prepareStatement("SELECT body FROM inventory.asset_identity WHERE tenant_id=? AND id=?")) {
            s.setString(1,tenant.value()); s.setObject(2,pin.id()); s.setQueryTimeout(5);
            try(var rows=s.executeQuery()) { if(!rows.next()) throw new SourceReview.Conflict("Identity missing"); AssetIdentityJson.decode(rows.getString(1)).requirePin(tenant,entity,pin); }
        }
    }
    @Override public Receipt change(TenantId tenant, EntityId entity, String namespace, AssetIdentity.Command command, Instant now) {
        AssetIdentity.namespace(namespace); Receipt[] result={null};
        Transactions.run(dataSource,c -> {
            PostgresSourceReviews.lock(c,tenant,entity);
            try(var s=c.prepareStatement("SELECT entity_id, entity_version, command, result FROM inventory.asset_identity_receipt WHERE tenant_id=? AND request_id=?")) {
                s.setString(1,tenant.value()); s.setObject(2,command.requestId()); s.setQueryTimeout(5);
                try(var rows=s.executeQuery()) { if(rows.next()) {
                    var old=AssetIdentityJson.decode(rows.getString("result")); var oldCommand=AssetIdentityJson.command(rows.getString("command"));
                    if(!entity.value().equals(rows.getObject("entity_id",UUID.class)) || !namespace.equals(old.namespace()) || !command.equals(oldCommand)) throw new SourceReview.Conflict("Identity request reused");
                    result[0]=new Receipt(command,old,rows.getLong("entity_version")); return;
                } }
            }
            var current=PostgresSourceReviews.entity(c,tenant,entity);
            if(current.version()!=command.expectedEntityVersion()) throw new SourceReview.Conflict("Entity changed");
            if(!current.entityType().equalsIgnoreCase("host")) throw new IllegalArgumentException("Asset UUID resolution requires a Host");
            final AssetIdentity changed;
            if(command.action()==AssetIdentity.Action.ASSERT) {
                try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                    s.setString(1,"asset-identity:"+tenant.value()+":"+namespace+":"+command.value()); s.setQueryTimeout(5); s.execute();
                }
                try(var s=c.prepareStatement("SELECT count(*), count(*) FILTER (WHERE active) FROM inventory.asset_identity WHERE tenant_id=? AND entity_id=?")) {
                    s.setString(1,tenant.value()); s.setObject(2,entity.value()); s.setQueryTimeout(5);
                    try(var rows=s.executeQuery()) { rows.next(); if(rows.getLong(1)>=AssetIdentity.MAX_RECORDS || rows.getLong(2)>=AssetIdentity.MAX_ACTIVE) throw new IllegalStateException("Identity retention budget exhausted"); }
                }
                changed=new AssetIdentity(command.identityId(),tenant,entity,namespace,command.value(),command.actor(),command.reason(),now,null);
                try(var s=c.prepareStatement("INSERT INTO inventory.asset_identity(tenant_id,id,entity_id,namespace,value,active,body) VALUES (?,?,?,?,?,true,?::jsonb) ON CONFLICT DO NOTHING")) {
                    s.setString(1,tenant.value()); s.setObject(2,changed.id()); s.setObject(3,entity.value()); s.setString(4,namespace); s.setString(5,changed.value()); s.setString(6,AssetIdentityJson.encode(changed)); s.setQueryTimeout(5);
                    if(s.executeUpdate()!=1) throw new SourceReview.Conflict("Identity is already claimed");
                }
            } else {
                AssetIdentity old;
                try(var s=c.prepareStatement("SELECT body FROM inventory.asset_identity WHERE tenant_id=? AND entity_id=? AND namespace=? AND id=?")) {
                    s.setString(1,tenant.value()); s.setObject(2,entity.value()); s.setString(3,namespace); s.setObject(4,command.identityId()); s.setQueryTimeout(5);
                    try(var rows=s.executeQuery()) { if(!rows.next()) throw new SourceReview.Conflict("Identity missing"); old=AssetIdentityJson.decode(rows.getString(1)); }
                }
                var active=PostgresSourceReviews.active(c,tenant,entity);
                if(active!=null && active.identity()!=null && active.identity().id().equals(old.id())) throw new SourceReview.Conflict("Revoke dependent source fields first");
                changed=old.revoke(command,now);
                try(var s=c.prepareStatement("UPDATE inventory.asset_identity SET active=false,body=?::jsonb WHERE tenant_id=? AND id=? AND active")) {
                    s.setString(1,AssetIdentityJson.encode(changed)); s.setString(2,tenant.value()); s.setObject(3,changed.id()); s.setQueryTimeout(5);
                    if(s.executeUpdate()!=1) throw new SourceReview.Conflict("Identity changed");
                }
            }
            long version=current.version()+1;
            try(var s=c.prepareStatement("UPDATE inventory.entity SET version=? WHERE tenant_id=? AND id=? AND version=?")) {
                s.setLong(1,version); s.setString(2,tenant.value()); s.setObject(3,entity.value()); s.setLong(4,current.version()); s.setQueryTimeout(5);
                if(s.executeUpdate()!=1) throw new SourceReview.Conflict("Entity changed");
            }
            try(var s=c.prepareStatement("INSERT INTO inventory.asset_identity_receipt(tenant_id,request_id,entity_id,identity_id,entity_version,command,result) VALUES (?,?,?,?,?,?::jsonb,?::jsonb) ON CONFLICT DO NOTHING")) {
                s.setString(1,tenant.value()); s.setObject(2,command.requestId()); s.setObject(3,entity.value()); s.setObject(4,changed.id()); s.setLong(5,version); s.setString(6,AssetIdentityJson.encode(command)); s.setString(7,AssetIdentityJson.encode(changed)); s.setQueryTimeout(5);
                if(s.executeUpdate()!=1) throw new SourceReview.Conflict("Identity request reused");
            }
            result[0]=new Receipt(command,changed,version);
        }); return result[0];
    }
    @Override public List<AssetIdentity> identities(TenantId tenant, EntityId entity, String namespace, UUID after, int limit) {
        AssetIdentity.namespace(namespace); if(limit<1 || limit>25) throw new IllegalArgumentException("Invalid identity page limit");
        try(var c=dataSource.getConnection();var s=c.prepareStatement("SELECT body FROM inventory.asset_identity WHERE tenant_id=? AND entity_id=? AND namespace=? AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT ?")) {
            s.setString(1,tenant.value()); s.setObject(2,entity.value()); s.setString(3,namespace); s.setObject(4,after); s.setObject(5,after); s.setInt(6,limit+1); s.setQueryTimeout(5);
            var result=new ArrayList<AssetIdentity>(); try(var rows=s.executeQuery()) { while(rows.next()) result.add(AssetIdentityJson.decode(rows.getString(1))); } return List.copyOf(result);
        } catch(SQLException failed) { throw new IllegalStateException("Asset identity page unavailable"); }
    }
    @Override public Optional<Match> resolve(TenantId tenant, String namespace, String value) {
        AssetIdentity.namespace(namespace); AssetIdentity.value(value);
        try(var c=dataSource.getConnection();var s=c.prepareStatement("SELECT i.body,e.version FROM inventory.asset_identity i JOIN inventory.entity e ON e.tenant_id=i.tenant_id AND e.id=i.entity_id WHERE i.tenant_id=? AND i.namespace=? AND i.value=? AND i.active")) {
            s.setString(1,tenant.value()); s.setString(2,namespace); s.setString(3,value); s.setQueryTimeout(5);
            try(var rows=s.executeQuery()) { return rows.next()?Optional.of(new Match(AssetIdentityJson.decode(rows.getString(1)),rows.getLong(2))):Optional.empty(); }
        } catch(SQLException failed) { throw new IllegalStateException("Asset identity resolver unavailable"); }
    }
}

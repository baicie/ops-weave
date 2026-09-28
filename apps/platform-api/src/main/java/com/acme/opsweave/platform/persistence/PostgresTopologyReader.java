package com.acme.opsweave.platform.persistence;
import com.acme.opsweave.inventory.api.TopologyReader;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.sharedkernel.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

final class PostgresTopologyReader implements TopologyReader {
    private final DataSource source;
    PostgresTopologyReader(DataSource source) { this.source=source; }
    private static final String VIEW="(SELECT * FROM "+PostgresSourceSnapshots.ENTITY_VIEW+")";
    private static final String NODE="%s.id AS %sid, %s.name AS %sname, %s.entity_type AS %stype, %s.lifecycle AS %slifecycle, %s.attributes->>'dataMode' AS %smode";
    private static String columns(String alias) { return NODE.formatted(alias,alias,alias,alias,alias,alias,alias,alias,alias,alias); }
    private static EntityTopology.Node node(ResultSet row,String prefix)throws SQLException {
        var origin=row.getString(prefix+"mode");
        origin="labeled-fixture".equals(origin)?"fixture":Set.of("fixture","zabbix-jsonrpc","import").contains(origin==null?"":origin)?origin:"unknown";
        return new EntityTopology.Node(new EntityId(row.getObject(prefix+"id",UUID.class)),row.getString(prefix+"name"),row.getString(prefix+"type"),row.getString(prefix+"lifecycle"),origin);
    }
    @Override public Optional<EntityTopology> read(TenantId tenant,EntityId center,EntityVisibility scope,Instant at) {
        if (!scope.includes(center)) return Optional.empty();
        try(var c=source.getConnection()) {
            c.setReadOnly(true); c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); c.setAutoCommit(false);
            try {
                var nodes=new LinkedHashMap<EntityId,EntityTopology.Node>();
                try(var q=c.prepareStatement("SELECT "+columns("a")+" FROM "+VIEW+" a WHERE a.tenant_id=? AND a.id=?")) {
                    q.setQueryTimeout(5); q.setString(1,tenant.value()); q.setObject(2,center.value());
                    try(var r=q.executeQuery()) { if (!r.next()) { c.commit(); return Optional.empty(); } var n=node(r,"a"); nodes.put(n.id(),n); }
                }
                var edges=new ArrayList<EntityTopology.Edge>(); boolean truncated=false;
                var ids=c.createArrayOf("uuid",scope.ids().stream().map(EntityId::value).toArray());
                try(var q=c.prepareStatement("""
                    SELECT r.id,r.from_entity_id,r.to_entity_id,r.relation_type,r.valid_from,r.valid_to,r.data_mode,%s,%s
                    FROM inventory.entity_relation r
                    JOIN %s a ON a.tenant_id=r.tenant_id AND a.id=r.from_entity_id
                    JOIN %s b ON b.tenant_id=r.tenant_id AND b.id=r.to_entity_id
                    WHERE r.tenant_id=? AND (r.from_entity_id=? OR r.to_entity_id=?)
                    AND (? OR (r.from_entity_id=ANY(?) AND r.to_entity_id=ANY(?)))
                    AND r.valid_from<=? AND (r.valid_to IS NULL OR r.valid_to>?)
                    ORDER BY r.id LIMIT 51
                    """.formatted(columns("a"),columns("b"),VIEW,VIEW))) {
                    q.setQueryTimeout(5);q.setString(1,tenant.value());q.setObject(2,center.value());q.setObject(3,center.value());q.setBoolean(4,scope.all());q.setArray(5,ids);q.setArray(6,ids);q.setTimestamp(7,Timestamp.from(at));q.setTimestamp(8,Timestamp.from(at));
                    try(var r=q.executeQuery()) { while(r.next()) { if(edges.size()==EntityTopology.LIMIT){truncated=true;break;}var a=node(r,"a");var b=node(r,"b");nodes.put(a.id(),a);nodes.put(b.id(),b);var until=r.getTimestamp("valid_to");edges.add(new EntityTopology.Edge(r.getObject("id",UUID.class),a.id(),b.id(),r.getString("relation_type"),r.getTimestamp("valid_from").toInstant(),until==null?null:until.toInstant(),r.getString("data_mode"))); } }
                } finally { ids.free(); }
                var result=new EntityTopology(tenant,center,at,List.copyOf(nodes.values()),edges,truncated);c.commit();return Optional.of(result);
            } catch(SQLException|RuntimeException failed) { c.rollback();throw failed; }
        } catch(SQLException failed) { throw new IllegalStateException("Topology read unavailable"); }
    }
}

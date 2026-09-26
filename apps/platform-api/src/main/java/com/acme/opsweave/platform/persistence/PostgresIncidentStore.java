package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.alerting.domain.ProblemObservation;
import com.acme.opsweave.incident.api.IncidentStore;
import com.acme.opsweave.incident.api.ProblemHistoryReader;
import com.acme.opsweave.incident.domain.*;
import com.acme.opsweave.platform.incident.IncidentJson;
import com.acme.opsweave.platform.incident.ReorganizationJson;
import com.acme.opsweave.sharedkernel.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import static com.acme.opsweave.incident.domain.IncidentFailure.Code.*;

final class PostgresIncidentStore implements IncidentStore, ProblemHistoryReader {
    private static final String SCOPE = "(? OR id = ANY (?)) AND (? OR (NOT has_unmapped AND cardinality(entity_ids) > 0 AND entity_ids <@ ?))";
    private final DataSource dataSource;
    PostgresIncidentStore(DataSource dataSource) { this.dataSource = dataSource; }

    public ImportResult ingest(TenantId tenant, String source, String mode, List<ExternalProblem> problems, Instant receivedAt) {
        IncidentStore.validatePage(tenant, source, mode, problems, receivedAt);
        if (problems.isEmpty()) return new ImportResult(0, 0, 0, 0);
        ImportResult[] result = new ImportResult[1];
        Transactions.run(dataSource, connection -> {
            limits(connection); ownershipLock(connection, tenant); long deadline = System.nanoTime() + 10_000_000_000L;
            var hosts = new HashSet<String>(); problems.forEach(p -> hosts.addAll(p.hostIds()));
            var mapped = resolve(connection, tenant, source, hosts);
            var created = new HashSet<UUID>(); var changed = new HashSet<UUID>(); int unmapped = 0;
            // Deterministic row-lock order avoids deadlocks between overlapping pages.
            var ordered = problems.stream().sorted(Comparator.comparing(p -> IncidentRecord.initialId(p).toString())).toList();
            for (var problem : ordered) {
                deadline(deadline);
                var links = new HashMap<String,EntityId>(); for (String host : problem.hostIds()) if (mapped.containsKey(host)) links.put(host, mapped.get(host));
                var initial = IncidentProjection.observe(null, problem, mode, links, receivedAt).record();
                var owner = occurrenceOwner(connection, tenant, source, problem.problemEventId(), mode);
                var id = owner == null ? initial.incident().id() : owner;
                boolean inserted = owner == null && insert(connection, initial);
                var previous = read(connection, tenant, id, null, true).orElseThrow(() -> new IncidentFailure(UNAVAILABLE));
                IncidentRecord stored;
                if (inserted) { created.add(id); stored = previous; }
                else {
                    var applied = IncidentProjection.observe(previous, problem, mode, links, receivedAt);
                    stored = applied.record(); if (applied.changed()) changed.add(id);
                    if (!stored.equals(previous)) save(connection, stored);
                }
                bindOccurrence(connection, tenant, source, problem.problemEventId(), mode, id);
                PostgresProblemHistory.capture(connection, ProblemObservation.capture(problem,mode,receivedAt,links));
                var storedProblem = stored.problems().stream().filter(p -> p.observation().sourceInstanceId().equals(source)
                    && p.observation().problemEventId().equals(problem.problemEventId())).findFirst().orElseThrow();
                unmapped += (int) storedProblem.observation().hostIds().stream().filter(host -> !storedProblem.entities().containsKey(host)).count();
            }
            changed.removeAll(created); deadline(deadline); result[0] = new ImportResult(problems.size(), created.size(), changed.size(), unmapped);
        });
        return result[0];
    }
    public Optional<IncidentRecord> find(TenantId tenant, UUID id, IncidentVisibility visibility) {
        try (var connection = dataSource.getConnection()) { return read(connection, tenant, id, visibility, false); }
        catch (SQLException failed) { throw new IncidentFailure(UNAVAILABLE); }
    }
    public List<ProblemObservation> observations(TenantId tenant, UUID id, IncidentVisibility visibility, ProblemHistoryQuery query) {
        try(var connection=dataSource.getConnection()) {
            connection.setReadOnly(true);connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);connection.setAutoCommit(false);
            limits(connection);
            var current=read(connection,tenant,id,visibility,false).orElseThrow(()->new IncidentFailure(NOT_FOUND));
            if(current.incident().version()!=query.incidentVersion())throw new IncidentFailure(CONFLICT);
            var result=PostgresProblemHistory.page(connection,tenant,id,visibility,query);
            connection.commit();return result;
        }catch(SQLException failed){throw new IncidentFailure(UNAVAILABLE);}
    }
    public List<IncidentRecord.Header> page(TenantId tenant, IncidentVisibility visibility, IncidentStatus status, UUID after, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid incident limit");
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
            SELECT tenant_id, id, title, status, severity, version, created_at_text FROM incident.incident
            WHERE tenant_id = ? AND merged_into IS NULL AND
            """ + SCOPE + " AND (?::varchar IS NULL OR status = ?) AND (?::uuid IS NULL OR id > ?::uuid) ORDER BY id LIMIT ?")) {
            statement.setQueryTimeout(3); statement.setString(1, tenant.value());
            try (var scope = bindScope(connection, statement, 2, visibility)) {
                statement.setString(6, status == null ? null : status.name()); statement.setString(7, status == null ? null : status.name());
                statement.setObject(8, after); statement.setObject(9, after); statement.setInt(10, limit + 1);
                var result = new ArrayList<IncidentRecord.Header>();
                try (var rows = statement.executeQuery()) { while (rows.next()) result.add(header(rows)); }
                return List.copyOf(result);
            }
        } catch (SQLException failed) { throw new IncidentFailure(UNAVAILABLE); }
    }
    public TransitionResult transition(TenantId tenant, UUID id, IncidentVisibility visibility, long expected,
            IncidentStatus target, UUID requestKey, String actor, Instant now) {
        TransitionResult[] result = new TransitionResult[1];
        Transactions.run(dataSource, connection -> {
            limits(connection);
            var previous = read(connection, tenant, id, visibility, true).orElseThrow(() -> new IncidentFailure(NOT_FOUND));
            try (var statement = connection.prepareStatement("SELECT actor, expected_version, target_status, result_version FROM incident.transition_request WHERE tenant_id=? AND incident_id=? AND request_key=?")) {
                statement.setString(1, tenant.value()); statement.setObject(2, id); statement.setObject(3, requestKey);
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        if (!actor.equals(rows.getString("actor")) || expected != rows.getLong("expected_version") || !target.name().equals(rows.getString("target_status"))) throw new IncidentFailure(CONFLICT);
                        result[0] = new TransitionResult(id, target, rows.getLong("result_version")); return;
                    }
                }
            }
            var updated = IncidentProjection.transition(previous, expected, target, requestKey, actor, now);
            save(connection, updated);
            try (var statement = connection.prepareStatement("INSERT INTO incident.transition_request (tenant_id,incident_id,request_key,actor,expected_version,target_status,result_version) VALUES (?,?,?,?,?,?,?)")) {
                statement.setString(1, tenant.value()); statement.setObject(2, id); statement.setObject(3, requestKey); statement.setString(4, actor);
                statement.setLong(5, expected); statement.setString(6, target.name()); statement.setLong(7, updated.incident().version()); statement.executeUpdate();
            }
            result[0] = new TransitionResult(id, target, updated.incident().version());
        });
        return result[0];
    }
    private static void limits(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute("SET LOCAL statement_timeout = '3s'"); statement.execute("SET LOCAL lock_timeout = '2s'"); }
    }
    // Association changes and low-throughput problem import serialize per tenant, not per metric point.
    private static void ownershipLock(Connection connection, TenantId tenant) throws SQLException {
        try (var s = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) { s.setString(1,"incident-ownership:"+tenant.value()); s.execute(); }
    }
    private static UUID occurrenceOwner(Connection connection, TenantId tenant, String source, String event, String mode) throws SQLException {
        try (var s = connection.prepareStatement("SELECT incident_id,data_mode FROM alerting.external_problem WHERE tenant_id=? AND source_instance_id=? AND problem_event_id=?")) {
            s.setString(1,tenant.value()); s.setString(2,source); s.setString(3,event);
            try (var rows=s.executeQuery()) { if (!rows.next()) return null; if (!mode.equals(rows.getString(2))) throw new IncidentFailure(CONFLICT); return rows.getObject(1,UUID.class); }
        }
    }
    public IncidentReorganization.Receipt reorganize(TenantId tenant, IncidentReorganization.Request request, IncidentVisibility visibility, String actor, Instant now) {
        IncidentReorganization.Receipt[] result = new IncidentReorganization.Receipt[1];
        Transactions.run(dataSource, connection -> {
            limits(connection); ownershipLock(connection,tenant); long deadline=System.nanoTime()+10_000_000_000L;
            // Both aggregate locks have a stable order; transition and diagnosis locks cannot invert it.
            var ordered=List.of(request.sourceIncidentId(),request.targetIncidentId()).stream().sorted(Comparator.comparing(UUID::toString)).toList();
            var records=new HashMap<UUID,IncidentRecord>();
            for (var id:ordered) read(connection,tenant,id,visibility,true).ifPresent(r -> records.put(id,r));
            var source=records.get(request.sourceIncidentId()); if (source==null) throw new IncidentFailure(NOT_FOUND);
            var saved=receipt(connection,tenant,request.requestKey());
            if (saved.isPresent()) {
                var value=saved.get();
                if (!value.actor().equals(actor) || !value.request().equals(request)) throw new IncidentFailure(CONFLICT);
                if (!records.containsKey(request.targetIncidentId())) throw new IncidentFailure(NOT_FOUND);
                result[0]=value; return;
            }
            var target=records.get(request.targetIncidentId());
            if (request.kind()==IncidentReorganization.Kind.MERGE && target==null) throw new IncidentFailure(NOT_FOUND);
            var applied=IncidentReorganization.apply(source,target,request,actor,now);
            if (!visibility.includes(applied.source()) || !visibility.includes(applied.target())) throw new IncidentFailure(FORBIDDEN);
            if (target==null) { if (!insert(connection,applied.target())) throw new IncidentFailure(CONFLICT); } else save(connection,applied.target());
            save(connection,applied.source());
            for (var key:applied.receipt().movedProblems()) {
                deadline(deadline);
                try (var s=connection.prepareStatement("UPDATE alerting.external_problem SET incident_id=? WHERE tenant_id=? AND source_instance_id=? AND problem_event_id=? AND incident_id=?")) {
                    s.setObject(1,request.targetIncidentId()); s.setString(2,tenant.value()); s.setString(3,key.sourceInstanceId()); s.setString(4,key.problemEventId()); s.setObject(5,request.sourceIncidentId());
                    if (s.executeUpdate()!=1) throw new IncidentFailure(CONFLICT);
                }
            }
            try (var s=connection.prepareStatement("INSERT INTO incident.reorganization_request(tenant_id,request_key,source_id,target_id,actor,receipt) VALUES (?,?,?,?,?,?::jsonb)")) {
                s.setString(1,tenant.value()); s.setObject(2,request.requestKey()); s.setObject(3,request.sourceIncidentId()); s.setObject(4,request.targetIncidentId()); s.setString(5,actor); s.setString(6,ReorganizationJson.encode(applied.receipt())); s.executeUpdate();
            }
            deadline(deadline); if (Thread.currentThread().isInterrupted()) throw new IncidentFailure(UNAVAILABLE);
            result[0]=applied.receipt();
        });
        return result[0];
    }
    public Optional<IncidentReorganization.Receipt> reorganization(TenantId tenant, UUID key, IncidentVisibility visibility) {
        try (var connection=dataSource.getConnection()) {
            var result=receipt(connection,tenant,key); if (result.isEmpty()) return result;
            var r=result.get(); if (read(connection,tenant,r.request().sourceIncidentId(),visibility,false).isEmpty() || read(connection,tenant,r.request().targetIncidentId(),visibility,false).isEmpty()) return Optional.empty();
            return result;
        } catch (SQLException error) { throw new IncidentFailure(UNAVAILABLE); }
    }
    private static Optional<IncidentReorganization.Receipt> receipt(Connection connection, TenantId tenant, UUID key) throws SQLException {
        try (var s=connection.prepareStatement("SELECT source_id,target_id,actor,receipt FROM incident.reorganization_request WHERE tenant_id=? AND request_key=?")) {
            s.setQueryTimeout(3); s.setString(1,tenant.value()); s.setObject(2,key);
            try (var rows=s.executeQuery()) {
                if (!rows.next()) return Optional.empty(); var r=ReorganizationJson.decode(rows.getString("receipt"));
                if (!r.request().requestKey().equals(key) || !r.actor().equals(rows.getString("actor")) || !r.request().sourceIncidentId().equals(rows.getObject("source_id",UUID.class)) || !r.request().targetIncidentId().equals(rows.getObject("target_id",UUID.class))) throw new IncidentFailure(UNAVAILABLE);
                return Optional.of(r);
            }
        }
    }
    public List<IncidentReorganization.Receipt> reorganizations(TenantId tenant, UUID incidentId, IncidentVisibility visibility, UUID after, int limit) {
        if (limit<1||limit>25) throw new IncidentFailure(INVALID_REQUEST);
        String scopeA="(? OR a.id=ANY(?)) AND (? OR (NOT a.has_unmapped AND cardinality(a.entity_ids)>0 AND a.entity_ids <@ ?))";
        String scopeB="(? OR b.id=ANY(?)) AND (? OR (NOT b.has_unmapped AND cardinality(b.entity_ids)>0 AND b.entity_ids <@ ?))";
        try(var c=dataSource.getConnection();var s=c.prepareStatement("SELECT r.request_key,r.source_id,r.target_id,r.receipt FROM incident.reorganization_request r JOIN incident.incident a ON a.tenant_id=r.tenant_id AND a.id=r.source_id JOIN incident.incident b ON b.tenant_id=r.tenant_id AND b.id=r.target_id WHERE r.tenant_id=? AND (r.source_id=? OR r.target_id=?) AND (?::uuid IS NULL OR r.request_key>?::uuid) AND "+scopeA+" AND "+scopeB+" ORDER BY r.request_key LIMIT ?")) {
            s.setQueryTimeout(3);s.setString(1,tenant.value());s.setObject(2,incidentId);s.setObject(3,incidentId);s.setObject(4,after);s.setObject(5,after);s.setInt(14,limit+1);
            try(var a=bindScope(c,s,6,visibility);var b=bindScope(c,s,10,visibility);var rows=s.executeQuery()) {
                var result=new ArrayList<IncidentReorganization.Receipt>();while(rows.next()){
                    var r=ReorganizationJson.decode(rows.getString("receipt"));
                    if(!r.request().requestKey().equals(rows.getObject("request_key",UUID.class))||!r.request().sourceIncidentId().equals(rows.getObject("source_id",UUID.class))||!r.request().targetIncidentId().equals(rows.getObject("target_id",UUID.class)))throw new IncidentFailure(UNAVAILABLE);
                    result.add(r);
                }return List.copyOf(result);
            }
        }catch(SQLException error){throw new IncidentFailure(UNAVAILABLE);}
    }
    private static void deadline(long deadline) { if (System.nanoTime() > deadline) throw new IncidentFailure(UNAVAILABLE); }
    private static Map<String,EntityId> resolve(Connection connection, TenantId tenant, String source, Set<String> hosts) throws SQLException {
        if (hosts.isEmpty()) return Map.of();
        Array array = connection.createArrayOf("varchar", hosts.toArray());
        try (var statement = connection.prepareStatement("""
            SELECT external_id, entity_id FROM inventory.entity_external_link
            WHERE tenant_id=? AND source_instance_id=? AND external_type='host' AND generation='1' AND external_id=ANY(?)
            """)) {
            statement.setString(1, tenant.value()); statement.setString(2, source); statement.setArray(3, array);
            var result = new HashMap<String,EntityId>();
            try (var rows = statement.executeQuery()) { while (rows.next()) result.put(rows.getString(1), new EntityId(rows.getObject(2, UUID.class))); }
            return result;
        } finally { array.free(); }
    }
    private static boolean insert(Connection connection, IncidentRecord record) throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO incident.incident (tenant_id,id,title,status,severity,version,created_at_text,entity_ids,has_unmapped,snapshot)
            VALUES (?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT (tenant_id,id) DO NOTHING
            """)) {
            var h = record.incident(); statement.setString(1, h.tenantId().value()); statement.setObject(2, h.id()); statement.setString(3, h.title());
            statement.setString(4, h.status().name()); statement.setInt(5, h.severity()); statement.setLong(6, h.version()); statement.setString(7, h.createdAt().toString());
            Array entities = connection.createArrayOf("uuid", record.entityIds().stream().map(EntityId::value).toArray());
            try { statement.setArray(8, entities); statement.setBoolean(9, record.hasUnmappedHosts()); statement.setString(10, IncidentJson.encode(record)); return statement.executeUpdate() == 1; }
            finally { entities.free(); }
        }
    }
    private static void save(Connection connection, IncidentRecord record) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE incident.incident SET title=?,status=?,severity=?,version=?,entity_ids=?,has_unmapped=?,snapshot=?::jsonb,merged_into=? WHERE tenant_id=? AND id=?")) {
            var h = record.incident(); statement.setString(1, h.title()); statement.setString(2, h.status().name()); statement.setInt(3, h.severity()); statement.setLong(4, h.version());
            Array entities = connection.createArrayOf("uuid", record.entityIds().stream().map(EntityId::value).toArray());
            try {
                statement.setArray(5, entities); statement.setBoolean(6, record.hasUnmappedHosts()); statement.setString(7, IncidentJson.encode(record)); statement.setObject(8, record.organization().mergedInto()); statement.setString(9, h.tenantId().value()); statement.setObject(10, h.id());
                if (statement.executeUpdate() != 1) throw new IncidentFailure(UNAVAILABLE);
            } finally { entities.free(); }
        }
    }
    private static void bindOccurrence(Connection connection, TenantId tenant, String source, String event, String mode, UUID incident) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO alerting.external_problem (tenant_id,source_instance_id,problem_event_id,incident_id,data_mode) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING")) {
            statement.setString(1, tenant.value()); statement.setString(2, source); statement.setString(3, event); statement.setObject(4, incident); statement.setString(5, mode); statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("SELECT incident_id,data_mode FROM alerting.external_problem WHERE tenant_id=? AND source_instance_id=? AND problem_event_id=?")) {
            statement.setString(1, tenant.value()); statement.setString(2, source); statement.setString(3, event);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !incident.equals(rows.getObject(1, UUID.class)) || !mode.equals(rows.getString(2))) throw new IncidentFailure(CONFLICT);
            }
        }
    }
    private static Optional<IncidentRecord> read(Connection connection, TenantId tenant, UUID id, IncidentVisibility visibility, boolean lock) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM incident.incident WHERE tenant_id=? AND id=?" + (visibility == null ? "" : " AND " + SCOPE) + (lock ? " FOR UPDATE" : ""))) {
            statement.setQueryTimeout(3); statement.setString(1, tenant.value()); statement.setObject(2, id);
            try (var scope = visibility == null ? new ScopeArrays(null, null) : bindScope(connection, statement, 3, visibility);
                 var rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                var record = IncidentJson.decode(rows.getString("snapshot"));
                if (!header(rows).equals(record.incident()) || rows.getBoolean("has_unmapped") != record.hasUnmappedHosts()
                    || !Objects.equals(rows.getObject("merged_into", UUID.class),record.organization().mergedInto())) throw new IncidentFailure(UNAVAILABLE);
                Array entityArray = rows.getArray("entity_ids");
                try {
                    var actual = new HashSet<EntityId>(); for (Object item : (Object[]) entityArray.getArray()) actual.add(EntityId.parse(item.toString()));
                    if (!actual.equals(record.entityIds()) || (visibility != null && !visibility.includes(record))) throw new IncidentFailure(UNAVAILABLE);
                } finally { entityArray.free(); }
                return Optional.of(record);
            }
        }
    }
    private static IncidentRecord.Header header(ResultSet rows) throws SQLException {
        return new IncidentRecord.Header(new TenantId(rows.getString("tenant_id")), rows.getObject("id", UUID.class), rows.getString("title"),
            IncidentStatus.valueOf(rows.getString("status")), rows.getInt("severity"), rows.getLong("version"), Instant.parse(rows.getString("created_at_text")));
    }
    private static ScopeArrays bindScope(Connection connection, PreparedStatement statement, int offset, IncidentVisibility visibility) throws SQLException {
        Array incidents = connection.createArrayOf("uuid", visibility.incidents().toArray());
        Array entities = connection.createArrayOf("uuid", visibility.entities().stream().map(EntityId::value).toArray());
        statement.setBoolean(offset, visibility.allIncidents()); statement.setArray(offset + 1, incidents);
        statement.setBoolean(offset + 2, visibility.allEntities()); statement.setArray(offset + 3, entities); return new ScopeArrays(incidents, entities);
    }
    private record ScopeArrays(Array incidents, Array entities) implements AutoCloseable {
        public void close() throws SQLException { if (incidents != null) incidents.free(); if (entities != null) entities.free(); }
    }
}

package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.inventory.api.EntityModelPinBackfillStore;
import com.acme.opsweave.inventory.domain.EntityModelPin;
import com.acme.opsweave.sharedkernel.TenantId;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicit legacy model-pin backfill with durable idempotent receipts. Rows are only pinned when
 * their stored attributes verifiably match the named published model; source-managed rows (the
 * ones with source observations) and already pinned rows are never touched, and nothing is
 * rewritten outside this command.
 */
final class PostgresEntityModelPinBackfillStore implements EntityModelPinBackfillStore {
    private final DataSource source;
    private final JsonMapper json = JsonMapper.builder().build();

    PostgresEntityModelPinBackfillStore(DataSource source) { this.source = source; }

    @Override
    public Receipt backfill(TenantId tenant, UUID requestId, String entityType, EntityModelPin model,
                            int maxUpdates, Conformance conformance, Instant now) {
        if (tenant == null || requestId == null || model == null || conformance == null || now == null) throw new IllegalArgumentException("Invalid backfill command");
        if (entityType == null || !entityType.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) throw new IllegalArgumentException("Invalid backfill command");
        if (maxUpdates < 1 || maxUpdates > 500) throw new IllegalArgumentException("Invalid backfill command");
        try (var c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (var lock = prepare(c, "SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                    lock.setString(1, "entity-model-pin-backfill:" + tenant.value() + ":" + requestId); lock.execute();
                }
                String requestBody = encodeRequest(entityType, model, maxUpdates);
                try (var q = prepare(c, "SELECT request, receipt FROM inventory.entity_model_pin_backfill_request WHERE tenant_id=? AND request_id=? FOR UPDATE")) {
                    q.setString(1, tenant.value()); q.setObject(2, requestId);
                    try (var r = q.executeQuery()) {
                        if (r.next()) {
                            if (!sameRequest(r.getString("request"), requestBody)) throw new Conflict("Backfill request reused");
                            c.commit();
                            return decodeReceipt(requestId, r.getString("receipt"), true);
                        }
                    }
                }
                int skippedPinned = count(c, "SELECT COUNT(*) FROM inventory.entity WHERE tenant_id=? AND entity_type=? AND model_id IS NOT NULL", tenant, entityType);
                int skippedSource = count(c, "SELECT COUNT(*) FROM inventory.entity e WHERE tenant_id=? AND entity_type=? AND e.model_id IS NULL AND EXISTS (SELECT 1 FROM inventory.entity_observation o WHERE o.tenant_id=e.tenant_id AND o.entity_id=e.id)", tenant, entityType);
                int scanned = 0, updated = 0, kept = 0;
                try (var select = prepare(c, "SELECT id, attributes FROM inventory.entity e WHERE tenant_id=? AND entity_type=? AND e.model_id IS NULL AND NOT EXISTS (SELECT 1 FROM inventory.entity_observation o WHERE o.tenant_id=e.tenant_id AND o.entity_id=e.id) ORDER BY id LIMIT ?")) {
                    select.setString(1, tenant.value()); select.setString(2, entityType); select.setInt(3, maxUpdates);
                    var rows = new ArrayList<Map.Entry<UUID, Map<String, Object>>>();
                    try (var r = select.executeQuery()) {
                        while (r.next()) rows.add(Map.entry((UUID) r.getObject("id"), attributes(r.getString("attributes"))));
                    }
                    for (var row : rows) {
                        scanned++;
                        boolean accepted;
                        try { accepted = conformance.accepts(row.getValue()); } catch (RuntimeException invalid) { accepted = false; }
                        if (!accepted) { kept++; continue; }
                        try (var update = prepare(c, "UPDATE inventory.entity SET model_id=?, model_revision=?, model_digest=? WHERE tenant_id=? AND id=? AND model_id IS NULL")) {
                            update.setString(1, model.id()); update.setInt(2, model.revision()); update.setString(3, model.digest());
                            update.setString(4, tenant.value()); update.setObject(5, row.getKey());
                            if (update.executeUpdate() != 1) throw new IllegalStateException("Entity model pin backfill unavailable");
                        }
                        updated++;
                    }
                }
                var counts = new Counts(scanned, updated, kept, skippedPinned, skippedSource);
                var receipt = new Receipt(requestId, entityType, model, counts, now, false);
                try (var insert = prepare(c, "INSERT INTO inventory.entity_model_pin_backfill_request(tenant_id,request_id,entity_type,request,receipt,created_at) VALUES(?,?,?,?::jsonb,?::jsonb,?)")) {
                    insert.setString(1, tenant.value()); insert.setObject(2, requestId); insert.setString(3, entityType);
                    insert.setString(4, requestBody); insert.setString(5, encodeReceipt(receipt)); insert.setTimestamp(6, Timestamp.from(now));
                    insert.executeUpdate();
                }
                c.commit();
                return receipt;
            } catch (RuntimeException | SQLException failed) {
                try { c.rollback(); } catch (SQLException ignored) {}
                if (failed instanceof Conflict conflict) throw conflict;
                if (failed instanceof IllegalStateException) throw failed;
                throw new IllegalStateException("Entity model pin backfill unavailable");
            }
        } catch (SQLException failed) { throw new IllegalStateException("Entity model pin backfill unavailable"); }
    }

    @Override
    public Optional<Receipt> receipt(TenantId tenant, UUID requestId) {
        try (var c = source.getConnection(); var q = prepare(c, "SELECT receipt FROM inventory.entity_model_pin_backfill_request WHERE tenant_id=? AND request_id=?")) {
            q.setString(1, tenant.value()); q.setObject(2, requestId);
            try (var r = q.executeQuery()) {
                return r.next() ? Optional.of(decodeReceipt(requestId, r.getString("receipt"), false)) : Optional.empty();
            }
        } catch (SQLException failed) { throw new IllegalStateException("Entity model pin backfill unavailable"); }
    }

    private int count(Connection c, String sql, TenantId tenant, String entityType) throws SQLException {
        try (var s = prepare(c, sql)) {
            s.setString(1, tenant.value()); s.setString(2, entityType);
            try (var r = s.executeQuery()) { return r.next() ? r.getInt(1) : 0; }
        }
    }

    private static PreparedStatement prepare(Connection c, String sql) throws SQLException {
        var s = c.prepareStatement(sql);
        s.setQueryTimeout(5);
        return s;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> attributes(String body) {
        try { return json.readValue(body, Map.class); } catch (RuntimeException invalid) { throw new IllegalStateException("Entity model pin backfill unavailable"); }
    }

    private String encodeRequest(String entityType, EntityModelPin model, int maxUpdates) {
        var pin = new LinkedHashMap<String, Object>(); pin.put("id", model.id()); pin.put("revision", model.revision()); pin.put("digest", model.digest());
        var body = new LinkedHashMap<String, Object>(); body.put("entityType", entityType); body.put("model", pin); body.put("maxUpdates", maxUpdates);
        return json.writeValueAsString(body);
    }

    @SuppressWarnings("unchecked")
    private boolean sameRequest(String stored, String current) {
        try {
            Map<String, Object> a = json.readValue(stored, Map.class);
            Map<String, Object> b = json.readValue(current, Map.class);
            return a.equals(b);
        } catch (RuntimeException invalid) { throw new Conflict("Backfill request reused"); }
    }

    private String encodeReceipt(Receipt receipt) {
        var pin = new LinkedHashMap<String, Object>(); pin.put("id", receipt.model().id()); pin.put("revision", receipt.model().revision()); pin.put("digest", receipt.model().digest());
        var request = new LinkedHashMap<String, Object>(); request.put("entityType", receipt.entityType()); request.put("model", pin);
        var counts = new LinkedHashMap<String, Object>();
        counts.put("scanned", receipt.counts().scanned()); counts.put("updated", receipt.counts().updated());
        counts.put("keptWithoutPin", receipt.counts().keptWithoutPin()); counts.put("skippedPinned", receipt.counts().skippedPinned());
        counts.put("skippedSource", receipt.counts().skippedSource());
        var body = new LinkedHashMap<String, Object>();
        body.put("requestId", receipt.requestId().toString()); body.put("request", request); body.put("counts", counts);
        body.put("completedAt", receipt.completedAt().toString());
        return json.writeValueAsString(body);
    }

    @SuppressWarnings("unchecked")
    private Receipt decodeReceipt(UUID requestId, String body, boolean replayed) {
        try {
            Map<String, Object> value = json.readValue(body, Map.class);
            Map<String, Object> request = (Map<String, Object>) value.get("request");
            Map<String, Object> pin = (Map<String, Object>) request.get("model");
            Map<String, Object> counts = (Map<String, Object>) value.get("counts");
            var model = new EntityModelPin(String.valueOf(pin.get("id")), ((Number) pin.get("revision")).intValue(), String.valueOf(pin.get("digest")));
            var result = new Counts(((Number) counts.get("scanned")).intValue(), ((Number) counts.get("updated")).intValue(),
                ((Number) counts.get("keptWithoutPin")).intValue(), ((Number) counts.get("skippedPinned")).intValue(),
                ((Number) counts.get("skippedSource")).intValue());
            return new Receipt(requestId, String.valueOf(request.get("entityType")), model, result,
                Instant.parse(String.valueOf(value.get("completedAt"))), replayed);
        } catch (RuntimeException invalid) { throw new IllegalStateException("Entity model pin backfill receipt unavailable"); }
    }
}

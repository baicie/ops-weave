package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.inventory.api.InventoryQuery;
import com.acme.opsweave.inventory.api.InventoryWritePort;
import com.acme.opsweave.inventory.domain.Entity;
import com.acme.opsweave.inventory.domain.ExternalLink;
import com.acme.opsweave.inventory.domain.Observation;
import com.acme.opsweave.inventory.domain.EntityPageQuery;
import com.acme.opsweave.inventory.domain.EntityVisibility;
import com.acme.opsweave.inventory.domain.EntityReadLimits;
import com.acme.opsweave.inventory.domain.SourceScan;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.sql.Array;
import com.acme.opsweave.platform.inventory.SourceReviewJson;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

final class PostgresInventoryStore implements InventoryQuery, InventoryWritePort, com.acme.opsweave.inventory.api.ObservationReader {
    private static final String OBS_COLUMNS = """
        id, entity_id, source_instance_id, external_type, external_id, generation,
        observed_epoch_nanos, ingested_epoch_nanos, exact_time, raw_record_ref, mapping_revision,
        CASE WHEN octet_length(fields::text) <= 16384 THEN fields END AS fields
        """;
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};
    private final DataSource dataSource;
    private final JsonMapper json = JsonMapper.builder().build();

    PostgresInventoryStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override public SourceScan.Token beginScan(SourceScan.Scope scope, java.util.UUID runId) {
        SourceScan.Token[] token = new SourceScan.Token[1];
        Transactions.run(dataSource, c -> {
            PostgresSourceScans.lock(c, scope);
            var lease = SourceScan.Lease.acquire(scope, runId, PostgresSourceScans.read(c, scope), PostgresSourceScans.now(c));
            PostgresSourceScans.write(c, lease);
            token[0] = lease.token();
        });
        return token[0];
    }
    @Override public void renewScan(SourceScan.Token token) {
        Transactions.run(dataSource, c -> {
            var lease = PostgresSourceScans.require(c, token);
            PostgresSourceScans.write(c, lease.renew(token, PostgresSourceScans.now(c)));
        });
    }
    @Override public void releaseScan(SourceScan.Token token) {
        Transactions.run(dataSource, c -> {
            PostgresSourceScans.lock(c, token.scope());
            var lease = PostgresSourceScans.read(c, token.scope());
            if (lease != null && lease.token().equals(token)) PostgresSourceScans.write(c, lease.release());
        });
    }
    @Override public void upsert(SourceScan.Token token, Entity entity, Observation observation, ExternalLink link) {
        Observation.checkWrite(entity, observation, link);
        token.scope().require(link.key());
        Transactions.run(dataSource, c -> {
            var lease = PostgresSourceScans.require(c, token);
            upsertInside(c, entity, observation, link);
            if (Thread.currentThread().isInterrupted()) throw new SourceScan.Failure(SourceScan.Code.DEADLINE);
            // Recheck after all potentially blocking writes: expiry rolls back this entire transaction.
            PostgresSourceScans.write(c, lease.renew(token, PostgresSourceScans.now(c)));
        });
    }
    @Override public int finishScan(SourceScan.Token token, Set<String> observed) {
        int[] count = {0};
        Transactions.run(dataSource, c -> {
            var lease = PostgresSourceScans.require(c, token);
            count[0] = retireInside(c, token.scope().tenantId(), token.scope().sourceInstanceId(), token.scope().externalType(), observed, lease);
            lease.require(token, PostgresSourceScans.now(c));
            if (Thread.currentThread().isInterrupted()) throw new SourceScan.Failure(SourceScan.Code.DEADLINE);
            PostgresSourceScans.write(c, lease.release());
        });
        return count[0];
    }

    @Override
    public Optional<EntityView> find(TenantId tenantId, EntityId entityId) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                SELECT entity_type, name, lifecycle, version,
                       CASE WHEN octet_length(attributes::text) <= 16384 THEN attributes END AS attributes
                  FROM %s
                 WHERE tenant_id = ? AND id = ?
                """.formatted(PostgresSourceSnapshots.ENTITY_VIEW))) {
            statement.setString(1, tenantId.value());
            statement.setObject(2, entityId.value());
            statement.setQueryTimeout(5);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(view(tenantId, entityId, rows));
            }
        } catch (SQLException failed) {
            throw new IllegalStateException("Entity lookup failed");
        }
    }

    @Override
    public List<EntityView> list(TenantId tenantId) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                SELECT id, entity_type, name, lifecycle, version,
                       CASE WHEN octet_length(attributes::text) <= 16384 THEN attributes END AS attributes
                  FROM %s
                 WHERE tenant_id = ?
                 ORDER BY name, id
                """.formatted(PostgresSourceSnapshots.ENTITY_VIEW))) {
            statement.setString(1, tenantId.value());
            statement.setQueryTimeout(5);
            try (ResultSet rows = statement.executeQuery()) {
                List<EntityView> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(view(tenantId, new EntityId(rows.getObject("id", java.util.UUID.class)), rows));
                }
                return List.copyOf(result);
            }
        } catch (SQLException failed) {
            throw new IllegalStateException("Entity list failed");
        }
    }

    @Override
    public List<EntityView> page(TenantId tenant, EntityVisibility visibility, EntityPageQuery query) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
            SELECT id, entity_type, name, lifecycle, version,
                   CASE WHEN octet_length(attributes::text) <= 16384 THEN attributes END AS attributes
            FROM %s
            WHERE tenant_id = ? AND (? OR id = ANY (?)) AND (?::uuid IS NULL OR id > ?::uuid)
              AND (? = '' OR POSITION(lower(?) IN lower(name)) > 0 OR POSITION(lower(?) IN lower(COALESCE(attributes->>'ip', ''))) > 0)
              AND (?::varchar IS NULL OR lifecycle = ?) AND (? = '' OR entity_type = ?)
            ORDER BY id LIMIT ?
            """.formatted(PostgresSourceSnapshots.ENTITY_VIEW))) {
            statement.setQueryTimeout(5);
            Array ids = connection.createArrayOf("uuid", visibility.ids().stream().map(EntityId::value).toArray());
            try {
                statement.setString(1, tenant.value()); statement.setBoolean(2, visibility.all()); statement.setArray(3, ids);
                var after = query.after() == null ? null : query.after().value();
                statement.setObject(4, after); statement.setObject(5, after);
                statement.setString(6, query.search()); statement.setString(7, query.search()); statement.setString(8, query.search());
                String lifecycle = query.lifecycle() == null ? null : query.lifecycle().name();
                statement.setString(9, lifecycle); statement.setString(10, lifecycle);
                statement.setString(11, query.entityType()); statement.setString(12, query.entityType()); statement.setInt(13, query.limit() + 1);
                var result = new ArrayList<EntityView>();
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) result.add(view(tenant, new EntityId(rows.getObject("id", java.util.UUID.class)), rows));
                }
                return List.copyOf(result);
            } finally { ids.free(); }
        } catch (SQLException failed) { throw new IllegalStateException("Entity page unavailable"); }
    }

    @Override
    public void upsert(Entity entity, Observation observation, ExternalLink link) {
        Observation.checkWrite(entity, observation, link);
        Transactions.run(dataSource, connection -> {
            PostgresSourceScans.unmanaged(connection,new SourceScan.Scope(link.key().tenantId(),link.key().sourceInstanceId(),link.key().externalType()));
            upsertInside(connection,entity,observation,link);
        });
    }
    private void upsertInside(java.sql.Connection connection,Entity entity,Observation observation,ExternalLink link)throws SQLException {
            PostgresSourceReviews.lock(connection, entity.tenantId(), entity.id());
            try (var lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                lock.setString(1, "observation:" + entity.tenantId().value().length() + ":" + entity.tenantId().value() + observation.id());
                lock.setQueryTimeout(5); lock.execute();
            }
            try (var existing = connection.prepareStatement("SELECT " + OBS_COLUMNS + " FROM inventory.entity_observation WHERE tenant_id = ? AND id = ?")) {
                existing.setString(1, entity.tenantId().value()); existing.setString(2, observation.id()); existing.setQueryTimeout(5);
                try (var rows = existing.executeQuery()) {
                    if (rows.next()) {
                        if (!readObservation(entity.tenantId(), rows).observation().equals(observation)) throw new IllegalStateException("Immutable observation conflict");
                        return;
                    }
                }
            }
            var projected = PostgresSourceReviews.incoming(connection, entity);
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO inventory.entity (
                    tenant_id, id, entity_type, name, lifecycle, version, attributes, last_seen_at, last_seen_epoch_nanos
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (tenant_id, id) DO UPDATE SET
                    entity_type = EXCLUDED.entity_type,
                    name = EXCLUDED.name,
                    lifecycle = EXCLUDED.lifecycle,
                    version = inventory.entity.version + 1,
                    attributes = EXCLUDED.attributes,
                    last_seen_at = EXCLUDED.last_seen_at,
                    last_seen_epoch_nanos = EXCLUDED.last_seen_epoch_nanos
                WHERE inventory.entity.last_seen_epoch_nanos <= EXCLUDED.last_seen_epoch_nanos
                """)) {
                statement.setString(1, entity.tenantId().value());
                statement.setObject(2, entity.id().value());
                statement.setString(3, entity.entityType());
                statement.setString(4, projected.name());
                statement.setString(5, projected.lifecycle().name());
                statement.setLong(6, entity.version());
                statement.setString(7, json.writeValueAsString(projected.attributes()));
                statement.setTimestamp(8, Timestamp.from(entity.lastSeen()));
                statement.setBigDecimal(9, nanos(entity.lastSeen())); statement.setQueryTimeout(5);
                statement.executeUpdate();
            }
            try (var guard = connection.prepareStatement("""
                SELECT 1 FROM inventory.entity_external_link WHERE tenant_id = ? AND entity_id = ?
                AND NOT (source_instance_id = ? AND external_type = ? AND external_id = ? AND generation = ?) LIMIT 1
                """)) {
                guard.setString(1, entity.tenantId().value()); guard.setObject(2, entity.id().value()); guard.setString(3, link.key().sourceInstanceId());
                guard.setString(4, link.key().externalType()); guard.setString(5, link.key().externalId()); guard.setString(6, link.key().generation()); guard.setQueryTimeout(5);
                try (var rows = guard.executeQuery()) { if (rows.next()) throw new IllegalStateException("Multiple sources require explicit field authority"); }
            }
            insertObservation(connection, observation);
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO inventory.entity_external_link (
                    tenant_id, source_instance_id, external_type, external_id, generation, entity_id
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, source_instance_id, external_type, external_id, generation)
                DO NOTHING
                """)) {
                statement.setString(1, link.key().tenantId().value());
                statement.setString(2, link.key().sourceInstanceId());
                statement.setString(3, link.key().externalType());
                statement.setString(4, link.key().externalId());
                statement.setString(5, link.key().generation());
                statement.setObject(6, link.entityId().value());
                statement.setQueryTimeout(5);
                statement.executeUpdate();
            }
            try (var guard = connection.prepareStatement("""
                SELECT entity_id FROM inventory.entity_external_link WHERE tenant_id = ? AND source_instance_id = ? AND external_type = ? AND external_id = ? AND generation = ?
                """)) {
                guard.setString(1, entity.tenantId().value()); guard.setString(2, link.key().sourceInstanceId()); guard.setString(3, link.key().externalType());
                guard.setString(4, link.key().externalId()); guard.setString(5, link.key().generation()); guard.setQueryTimeout(5);
                try (var rows = guard.executeQuery()) { if (!rows.next() || !entity.id().value().equals(rows.getObject(1, java.util.UUID.class))) throw new IllegalStateException("External link reassignment requires resolution"); }
            }
    }

    static void insertObservation(java.sql.Connection connection, Observation observation) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO inventory.entity_observation (
                    tenant_id, id, entity_id, source_instance_id, external_type, external_id, generation,
                    observed_at, ingested_at, fields, raw_record_ref, mapping_revision, observed_epoch_nanos, ingested_epoch_nanos, exact_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, true)
                """)) {
                statement.setString(1, observation.key().tenantId().value());
                statement.setString(2, observation.id());
                statement.setObject(3, observation.entityId().value());
                statement.setString(4, observation.key().sourceInstanceId());
                statement.setString(5, observation.key().externalType());
                statement.setString(6, observation.key().externalId());
                statement.setString(7, observation.key().generation());
                statement.setTimestamp(8, Timestamp.from(observation.observedAt()));
                statement.setTimestamp(9, Timestamp.from(observation.ingestedAt()));
                statement.setString(10, SourceReviewJson.JSON.writeValueAsString(observation.fields()));
                statement.setString(11, observation.rawRecordRef());
                statement.setInt(12, observation.mappingRevision());
                statement.setBigDecimal(13, nanos(observation.observedAt())); statement.setBigDecimal(14, nanos(observation.ingestedAt())); statement.setQueryTimeout(5);
                statement.executeUpdate();
            }
    }

    @Override public List<com.acme.opsweave.inventory.api.ObservationReader.Entry> observations(TenantId tenant, EntityId entity,
            com.acme.opsweave.inventory.domain.ObservationQuery query) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("SELECT " + OBS_COLUMNS + """
             FROM inventory.entity_observation WHERE tenant_id = ? AND entity_id = ?
             AND observed_epoch_nanos >= ? AND observed_epoch_nanos <= ? AND ingested_epoch_nanos <= ?
             AND (? = '' OR source_instance_id = ?) AND (?::text IS NULL OR id COLLATE "C" > ? COLLATE "C")
             ORDER BY id COLLATE "C" LIMIT ?
             """)) {
            statement.setString(1, tenant.value()); statement.setObject(2, entity.value());
            statement.setBigDecimal(3, nanos(java.time.Instant.ofEpochSecond(query.from()))); statement.setBigDecimal(4, nanos(java.time.Instant.ofEpochSecond(query.till())));
            statement.setBigDecimal(5, nanos(query.asOf())); statement.setString(6, query.source()); statement.setString(7, query.source());
            statement.setString(8, query.after()); statement.setString(9, query.after()); statement.setInt(10, query.limit() + 1); statement.setQueryTimeout(5);
            var result = new ArrayList<com.acme.opsweave.inventory.api.ObservationReader.Entry>();
            try (var rows = statement.executeQuery()) { while (rows.next()) result.add(readObservation(tenant, rows)); }
            return List.copyOf(result);
        } catch (SQLException failed) { throw new IllegalStateException("Observation history unavailable"); }
    }
    private com.acme.opsweave.inventory.api.ObservationReader.Entry readObservation(TenantId tenant, ResultSet row) throws SQLException {
        String fields = row.getString("fields"); if (fields == null) throw new IllegalStateException("Observation fields exceed read budget");
        var observation = new Observation(row.getString("id"), new com.acme.opsweave.inventory.domain.ExternalObjectKey(tenant,
            row.getString("source_instance_id"), row.getString("external_type"), row.getString("external_id"), row.getString("generation")),
            new EntityId(row.getObject("entity_id", java.util.UUID.class)), instant(row.getBigDecimal("observed_epoch_nanos")), instant(row.getBigDecimal("ingested_epoch_nanos")),
            json.readValue(fields, MAP), row.getString("raw_record_ref"), row.getInt("mapping_revision"));
        return new com.acme.opsweave.inventory.api.ObservationReader.Entry(observation, row.getBoolean("exact_time"));
    }
    static java.math.BigDecimal nanos(java.time.Instant value) {
        return java.math.BigDecimal.valueOf(value.getEpochSecond()).movePointRight(9).add(java.math.BigDecimal.valueOf(value.getNano()));
    }
    static java.time.Instant instant(java.math.BigDecimal value) {
        var parts = value.toBigIntegerExact().divideAndRemainder(java.math.BigInteger.valueOf(1_000_000_000));
        return java.time.Instant.ofEpochSecond(parts[0].longValueExact(), parts[1].longValueExact());
    }

    @Override
    public int retireMissing(TenantId tenantId, String sourceInstanceId, String externalType, Set<String> observedExternalIds) {
        int[] updated = {0};
        Transactions.run(dataSource, connection -> {
            PostgresSourceScans.unmanaged(connection,new SourceScan.Scope(tenantId,sourceInstanceId,externalType));
            updated[0]=retireInside(connection,tenantId,sourceInstanceId,externalType,observedExternalIds,null);
        });return updated[0];
    }
    private int retireInside(java.sql.Connection connection,TenantId tenantId,String sourceInstanceId,String externalType,Set<String> observedExternalIds,SourceScan.Lease lease)throws SQLException {
            int updated=0;
            Array observed = connection.createArrayOf("varchar", observedExternalIds.toArray(String[]::new));
            var ids = new ArrayList<EntityId>();
            try (var statement = connection.prepareStatement("SELECT DISTINCT entity_id FROM inventory.entity_external_link WHERE tenant_id = ? AND source_instance_id = ? AND external_type = ? AND NOT (external_id = ANY (?)) ORDER BY entity_id")) {
                statement.setString(1, tenantId.value()); statement.setString(2, sourceInstanceId); statement.setString(3, externalType); statement.setArray(4, observed); statement.setQueryTimeout(5);
                try (var rows = statement.executeQuery()) { while (rows.next()) ids.add(new EntityId(rows.getObject(1, java.util.UUID.class))); }
            } finally { observed.free(); }
            for (var id : ids) {
                if (lease != null) lease.require(lease.token(), PostgresSourceScans.now(connection));
                PostgresSourceReviews.lock(connection, tenantId, id);
                var current = PostgresSourceReviews.entity(connection, tenantId, id);
                var primary = PostgresSourceReviews.primary(connection, tenantId, id);
                if (primary == null) primary = current;
                if (primary.lifecycle() == com.acme.opsweave.inventory.domain.Lifecycle.INACTIVE) continue;
                var inactive = com.acme.opsweave.inventory.domain.FieldAuthority.inactive(primary);
                PostgresSourceReviews.primary(connection, inactive);
                PostgresSourceReviews.updateEntity(connection, PostgresSourceSnapshots.project(connection,inactive,
                    PostgresSourceReviews.active(connection,tenantId,id),current.version()+1));
                updated++;
            }
        return updated;
    }

    private EntityView view(TenantId tenantId, EntityId entityId, ResultSet rows) throws SQLException {
        String encoded = rows.getString("attributes");
        if (encoded == null) throw new IllegalStateException("Entity attributes exceed read budget");
        Map<String, Object> attributes = json.readValue(encoded, MAP);
        EntityReadLimits.check(attributes);
        return new EntityView(
            entityId,
            tenantId,
            rows.getString("entity_type"),
            rows.getString("name"),
            rows.getString("lifecycle"),
            rows.getLong("version"),
            attributes
        );
    }
}

package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.inventory.api.InventoryQuery;
import com.acme.opsweave.inventory.api.InventoryWritePort;
import com.acme.opsweave.inventory.domain.Entity;
import com.acme.opsweave.inventory.domain.ExternalLink;
import com.acme.opsweave.inventory.domain.Observation;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.sql.Array;
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

final class PostgresInventoryStore implements InventoryQuery, InventoryWritePort {
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};
    private final DataSource dataSource;
    private final JsonMapper json = JsonMapper.builder().build();

    PostgresInventoryStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<EntityView> find(TenantId tenantId, EntityId entityId) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                SELECT entity_type, name, lifecycle, version, attributes
                  FROM inventory.entity
                 WHERE tenant_id = ? AND id = ?
                """)) {
            statement.setString(1, tenantId.value());
            statement.setObject(2, entityId.value());
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
                SELECT id, entity_type, name, lifecycle, version, attributes
                  FROM inventory.entity
                 WHERE tenant_id = ?
                 ORDER BY name, id
                """)) {
            statement.setString(1, tenantId.value());
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
    public void upsert(Entity entity, Observation observation, ExternalLink link) {
        String attributes = json.writeValueAsString(entity.attributes());
        String fields = json.writeValueAsString(observation.fields());
        Transactions.run(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO inventory.entity (
                    tenant_id, id, entity_type, name, lifecycle, version, attributes, last_seen_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (tenant_id, id) DO UPDATE SET
                    entity_type = EXCLUDED.entity_type,
                    name = EXCLUDED.name,
                    lifecycle = EXCLUDED.lifecycle,
                    version = inventory.entity.version + 1,
                    attributes = EXCLUDED.attributes,
                    last_seen_at = EXCLUDED.last_seen_at
                """)) {
                statement.setString(1, entity.tenantId().value());
                statement.setObject(2, entity.id().value());
                statement.setString(3, entity.entityType());
                statement.setString(4, entity.name());
                statement.setString(5, entity.lifecycle().name());
                statement.setLong(6, entity.version());
                statement.setString(7, attributes);
                statement.setTimestamp(8, Timestamp.from(entity.lastSeen()));
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO inventory.entity_observation (
                    tenant_id, id, entity_id, source_instance_id, external_type, external_id, generation,
                    observed_at, ingested_at, fields, raw_record_ref, mapping_revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
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
                statement.setString(10, fields);
                statement.setString(11, observation.rawRecordRef());
                statement.setInt(12, observation.mappingRevision());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO inventory.entity_external_link (
                    tenant_id, source_instance_id, external_type, external_id, generation, entity_id
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, source_instance_id, external_type, external_id, generation)
                DO UPDATE SET entity_id = EXCLUDED.entity_id
                """)) {
                statement.setString(1, link.key().tenantId().value());
                statement.setString(2, link.key().sourceInstanceId());
                statement.setString(3, link.key().externalType());
                statement.setString(4, link.key().externalId());
                statement.setString(5, link.key().generation());
                statement.setObject(6, link.entityId().value());
                statement.executeUpdate();
            }
        });
    }

    @Override
    public int retireMissing(TenantId tenantId, String sourceInstanceId, String externalType, Set<String> seenExternalIds) {
        int[] updated = {0};
        Transactions.run(dataSource, connection -> {
            Array seen = connection.createArrayOf("varchar", seenExternalIds.toArray(String[]::new));
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE inventory.entity AS entity
                   SET lifecycle = 'INACTIVE', version = entity.version + 1
                 WHERE entity.tenant_id = ?
                   AND entity.lifecycle <> 'INACTIVE'
                   AND entity.id IN (
                       SELECT link.entity_id
                         FROM inventory.entity_external_link AS link
                        WHERE link.tenant_id = ?
                          AND link.source_instance_id = ?
                          AND link.external_type = ?
                          AND NOT (link.external_id = ANY (?))
                   )
                """)) {
                statement.setString(1, tenantId.value());
                statement.setString(2, tenantId.value());
                statement.setString(3, sourceInstanceId);
                statement.setString(4, externalType);
                statement.setArray(5, seen);
                updated[0] = statement.executeUpdate();
            }
        });
        return updated[0];
    }

    private EntityView view(TenantId tenantId, EntityId entityId, ResultSet rows) throws SQLException {
        Map<String, Object> attributes = json.readValue(rows.getString("attributes"), MAP);
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

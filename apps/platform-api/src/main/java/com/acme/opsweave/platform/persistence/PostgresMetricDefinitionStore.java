package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.api.MetricDefinitionStore;
import com.acme.opsweave.telemetry.domain.ExternalMetricMapping;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import com.acme.opsweave.telemetry.domain.MetricLifecycle;
import com.acme.opsweave.telemetry.domain.MetricOrigin;
import com.acme.opsweave.telemetry.domain.MetricType;
import com.acme.opsweave.telemetry.domain.MetricValueType;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

final class PostgresMetricDefinitionStore implements MetricDefinitionStore {
    private static final TypeReference<LinkedHashMap<String, String>> MAP = new TypeReference<>() {};
    private final DataSource dataSource;
    private final JsonMapper json = JsonMapper.builder().build();

    PostgresMetricDefinitionStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void upsert(MetricDefinition definition) {
        Transactions.run(dataSource, connection -> {
            String dimensions = json.writeValueAsString(definition.dimensions());
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO telemetry.metric_definition (
                    tenant_id, id, name, display_name, entity_type, unit, value_type, metric_type,
                    dimensions, origin, source_instance_id, external_id, item_key, host_external_id,
                    source_unit, value_transform, mapping_revision, lifecycle, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, id) DO UPDATE SET
                    name = EXCLUDED.name,
                    display_name = EXCLUDED.display_name,
                    entity_type = EXCLUDED.entity_type,
                    unit = EXCLUDED.unit,
                    value_type = EXCLUDED.value_type,
                    metric_type = EXCLUDED.metric_type,
                    dimensions = EXCLUDED.dimensions,
                    origin = EXCLUDED.origin,
                    source_instance_id = EXCLUDED.source_instance_id,
                    external_id = EXCLUDED.external_id,
                    item_key = EXCLUDED.item_key,
                    host_external_id = EXCLUDED.host_external_id,
                    source_unit = EXCLUDED.source_unit,
                    value_transform = EXCLUDED.value_transform,
                    mapping_revision = EXCLUDED.mapping_revision,
                    lifecycle = EXCLUDED.lifecycle,
                    version = telemetry.metric_definition.version + 1
                """)) {
                bind(statement, definition, dimensions);
                statement.executeUpdate();
            }
        });
    }

    @Override
    public int retireMissing(TenantId tenantId, String sourceInstanceId, Set<String> observedExternalIds) {
        int[] updated = {0};
        Transactions.run(dataSource, connection -> {
            Array observed = connection.createArrayOf("varchar", observedExternalIds.toArray(String[]::new));
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE telemetry.metric_definition
                   SET lifecycle = 'INACTIVE', version = version + 1
                 WHERE tenant_id = ?
                   AND source_instance_id = ?
                   AND lifecycle <> 'INACTIVE'
                   AND NOT (external_id = ANY (?))
                """)) {
                statement.setString(1, tenantId.value());
                statement.setString(2, sourceInstanceId);
                statement.setArray(3, observed);
                updated[0] = statement.executeUpdate();
            }
        });
        return updated[0];
    }

    @Override
    public Optional<MetricDefinition> find(TenantId tenantId, String id) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                SELECT id, name, display_name, entity_type, unit, value_type, metric_type, dimensions, origin,
                       source_instance_id, external_id, item_key, host_external_id, source_unit, value_transform,
                       mapping_revision, lifecycle, version
                  FROM telemetry.metric_definition
                 WHERE tenant_id = ? AND id = ?
                """)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(read(tenantId, rows));
            }
        } catch (SQLException failed) {
            throw new IllegalStateException("Inventory database write failed");
        }
    }

    @Override
    public List<MetricDefinition> list(TenantId tenantId) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                SELECT id, name, display_name, entity_type, unit, value_type, metric_type, dimensions, origin,
                       source_instance_id, external_id, item_key, host_external_id, source_unit, value_transform,
                       mapping_revision, lifecycle, version
                  FROM telemetry.metric_definition
                 WHERE tenant_id = ?
                 ORDER BY name, external_id
                """)) {
            statement.setString(1, tenantId.value());
            try (ResultSet rows = statement.executeQuery()) {
                List<MetricDefinition> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(read(tenantId, rows));
                }
                return List.copyOf(result);
            }
        } catch (SQLException failed) {
            throw new IllegalStateException("Inventory database write failed");
        }
    }

    private static void bind(PreparedStatement statement, MetricDefinition definition, String dimensions) throws SQLException {
        ExternalMetricMapping mapping = definition.externalMapping();
        statement.setString(1, definition.tenantId().value());
        statement.setString(2, definition.id());
        statement.setString(3, definition.name());
        statement.setString(4, definition.displayName());
        statement.setString(5, definition.entityType());
        statement.setString(6, definition.unit());
        statement.setString(7, definition.valueType().name());
        statement.setString(8, definition.metricType().name());
        statement.setString(9, dimensions);
        statement.setString(10, definition.origin().wireValue());
        statement.setString(11, mapping.sourceInstanceId());
        statement.setString(12, mapping.externalId());
        statement.setString(13, mapping.itemKey());
        statement.setString(14, mapping.hostExternalId());
        statement.setString(15, mapping.sourceUnit());
        statement.setString(16, mapping.valueTransform());
        statement.setInt(17, mapping.mappingRevision());
        statement.setString(18, definition.lifecycle().name());
        statement.setLong(19, definition.version());
    }

    private MetricDefinition read(TenantId tenantId, ResultSet rows) throws SQLException {
        LinkedHashMap<String, String> dimensions = json.readValue(rows.getString("dimensions"), MAP);
        return new MetricDefinition(
            rows.getString("id"),
            tenantId,
            rows.getString("name"),
            rows.getString("display_name"),
            rows.getString("entity_type"),
            rows.getString("unit"),
            MetricValueType.valueOf(rows.getString("value_type")),
            MetricType.valueOf(rows.getString("metric_type")),
            dimensions,
            MetricOrigin.SOURCE,
            new ExternalMetricMapping(
                "zabbix",
                rows.getString("source_instance_id"),
                rows.getString("external_id"),
                rows.getString("item_key"),
                rows.getString("host_external_id"),
                rows.getString("source_unit"),
                rows.getString("value_transform"),
                rows.getInt("mapping_revision")
            ),
            MetricLifecycle.valueOf(rows.getString("lifecycle")),
            rows.getLong("version")
        );
    }
}

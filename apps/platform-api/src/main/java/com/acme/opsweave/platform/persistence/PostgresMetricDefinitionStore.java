package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.integration.api.SourceItemWritePort;
import com.acme.opsweave.inventory.domain.SourceScan;
import com.acme.opsweave.telemetry.api.MetricDefinitionStore;
import com.acme.opsweave.telemetry.domain.MetricBinding;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import com.acme.opsweave.telemetry.domain.MetricLifecycle;
import com.acme.opsweave.telemetry.domain.MetricType;
import com.acme.opsweave.telemetry.domain.MetricValueType;
import java.sql.Array;
import java.sql.Connection;
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

final class PostgresMetricDefinitionStore implements MetricDefinitionStore, SourceItemWritePort {
    private static final TypeReference<List<String>> NAMES = new TypeReference<>() {};
    private static final TypeReference<LinkedHashMap<String, String>> MAP = new TypeReference<>() {};
    private final DataSource dataSource;
    private final JsonMapper json = JsonMapper.builder().build();

    PostgresMetricDefinitionStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void upsert(MetricDefinition definition) {
        Transactions.run(dataSource, connection -> upsertDefinition(connection, definition));
    }

    @Override
    public void upsert(MetricBinding binding) {
        Transactions.run(dataSource, connection -> upsertBinding(connection, binding));
    }

    @Override
    public void upsert(SourceScan.Token scan, MetricDefinition definition, MetricBinding binding) {
        Transactions.run(dataSource, connection -> {
            var lease = PostgresSourceScans.require(connection, scan);
            upsertDefinition(connection, definition);
            upsertBinding(connection, binding);
            if (Thread.currentThread().isInterrupted()) throw new SourceScan.Failure(SourceScan.Code.DEADLINE);
            // Recheck after all potentially blocking writes: expiry rolls back this entire transaction.
            PostgresSourceScans.write(connection, lease.renew(scan, PostgresSourceScans.now(connection)));
        });
    }

    @Override
    public int retireMissing(SourceScan.Token scan, Set<String> observedExternalIds) {
        int[] retired = {0};
        Transactions.run(dataSource, connection -> {
            var lease = PostgresSourceScans.require(connection, scan);
            retired[0] = retireInside(connection, scan.scope().tenantId(), scan.scope().sourceInstanceId(), observedExternalIds);
            if (Thread.currentThread().isInterrupted()) throw new SourceScan.Failure(SourceScan.Code.DEADLINE);
            lease.require(scan, PostgresSourceScans.now(connection));
            PostgresSourceScans.write(connection, lease.renew(scan, PostgresSourceScans.now(connection)));
        });
        return retired[0];
    }

    @Override
    public int retireMissing(TenantId tenantId, String sourceInstanceId, Set<String> observedExternalIds) {
        int[] retired = new int[1];
        Transactions.run(dataSource, connection ->
            retired[0] = retireInside(connection, tenantId, sourceInstanceId, observedExternalIds));
        return retired[0];
    }

    private void upsertDefinition(Connection connection, MetricDefinition definition) throws SQLException {
        String schema = json.writeValueAsString(definition.dimensionSchema());
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO telemetry.metric_definition (
                tenant_id, metric_key, display_name, unit, value_type, metric_type, dimension_schema, version
            ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (tenant_id, metric_key) DO UPDATE SET
                display_name = EXCLUDED.display_name,
                unit = EXCLUDED.unit,
                value_type = EXCLUDED.value_type,
                metric_type = EXCLUDED.metric_type,
                dimension_schema = EXCLUDED.dimension_schema,
                version = telemetry.metric_definition.version + 1
            """)) {
            statement.setString(1, definition.tenantId().value());
            statement.setString(2, definition.metricKey());
            statement.setString(3, definition.displayName());
            statement.setString(4, definition.unit());
            statement.setString(5, definition.valueType().name());
            statement.setString(6, definition.metricType().name());
            statement.setString(7, schema);
            statement.setLong(8, definition.version());
            statement.executeUpdate();
        }
    }

    private void upsertBinding(Connection connection, MetricBinding binding) throws SQLException {
        String dimensions = json.writeValueAsString(binding.fixedDimensions());
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO telemetry.metric_binding (
                tenant_id, source_instance_id, external_item_id, source_type, entity_id, host_external_id,
                metric_key, fixed_dimensions, source_unit, value_transform, mapping_revision, lifecycle, version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, source_instance_id, external_item_id) DO UPDATE SET
                source_type = EXCLUDED.source_type,
                entity_id = EXCLUDED.entity_id,
                host_external_id = EXCLUDED.host_external_id,
                metric_key = EXCLUDED.metric_key,
                fixed_dimensions = EXCLUDED.fixed_dimensions,
                source_unit = EXCLUDED.source_unit,
                value_transform = EXCLUDED.value_transform,
                mapping_revision = EXCLUDED.mapping_revision,
                lifecycle = EXCLUDED.lifecycle,
                version = telemetry.metric_binding.version + 1
            """)) {
            statement.setString(1, binding.tenantId().value());
            statement.setString(2, binding.sourceInstanceId());
            statement.setString(3, binding.externalItemId());
            statement.setString(4, binding.sourceType());
            statement.setString(5, binding.entityId().value().toString());
            statement.setString(6, binding.hostExternalId());
            statement.setString(7, binding.metricKey());
            statement.setString(8, dimensions);
            statement.setString(9, binding.sourceUnit());
            statement.setString(10, binding.valueTransform());
            statement.setInt(11, binding.mappingRevision());
            statement.setString(12, binding.lifecycle().name());
            statement.setLong(13, binding.version());
            statement.executeUpdate();
        }
    }

    private static int retireInside(
        Connection connection,
        TenantId tenantId,
        String sourceInstanceId,
        Set<String> observedExternalIds
    ) throws SQLException {
        Array observed = connection.createArrayOf("varchar", observedExternalIds.toArray(String[]::new));
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE telemetry.metric_binding
            SET lifecycle = 'INACTIVE', version = version + 1
            WHERE tenant_id = ? AND source_instance_id = ? AND lifecycle <> 'INACTIVE'
              AND NOT (external_item_id = ANY (?))
            """)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, sourceInstanceId);
            statement.setArray(3, observed);
            return statement.executeUpdate();
        }
    }

    @Override
    public Optional<MetricDefinition> find(TenantId tenantId, String metricKey) {
        return one(tenantId, """
            SELECT metric_key, display_name, unit, value_type, metric_type, dimension_schema, version
            FROM telemetry.metric_definition
            WHERE tenant_id = ? AND metric_key = ?
            """, metricKey);
    }

    @Override
    public Optional<MetricBinding> findBinding(TenantId tenantId, String sourceInstanceId, String externalItemId) {
        List<MetricBinding> found = new ArrayList<>();
        Transactions.run(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT source_instance_id, external_item_id, source_type, entity_id, host_external_id, metric_key,
                       fixed_dimensions, source_unit, value_transform, mapping_revision, lifecycle, version
                FROM telemetry.metric_binding
                WHERE tenant_id = ? AND source_instance_id = ? AND external_item_id = ?
                """)) {
                statement.setString(1, tenantId.value());
                statement.setString(2, sourceInstanceId);
                statement.setString(3, externalItemId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) {
                        found.add(readBinding(tenantId, rows));
                    }
                }
            }
        });
        return found.stream().findFirst();
    }

    @Override
    public List<MetricDefinition> list(TenantId tenantId) {
        List<MetricDefinition> result = new ArrayList<>();
        Transactions.run(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT metric_key, display_name, unit, value_type, metric_type, dimension_schema, version
                FROM telemetry.metric_definition
                WHERE tenant_id = ?
                ORDER BY metric_key
                """)) {
                statement.setString(1, tenantId.value());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(readDefinition(tenantId, rows));
                    }
                }
            }
        });
        return List.copyOf(result);
    }

    @Override
    public List<MetricBinding> listBindings(TenantId tenantId) {
        List<MetricBinding> result = new ArrayList<>();
        Transactions.run(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT source_instance_id, external_item_id, source_type, entity_id, host_external_id, metric_key,
                       fixed_dimensions, source_unit, value_transform, mapping_revision, lifecycle, version
                FROM telemetry.metric_binding
                WHERE tenant_id = ?
                ORDER BY external_item_id
                """)) {
                statement.setString(1, tenantId.value());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(readBinding(tenantId, rows));
                    }
                }
            }
        });
        return List.copyOf(result);
    }

    private Optional<MetricDefinition> one(TenantId tenantId, String sql, String metricKey) {
        List<MetricDefinition> found = new ArrayList<>();
        Transactions.run(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tenantId.value());
                statement.setString(2, metricKey);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) {
                        found.add(readDefinition(tenantId, rows));
                    }
                }
            }
        });
        return found.stream().findFirst();
    }

    private MetricDefinition readDefinition(TenantId tenantId, ResultSet rows) throws SQLException {
        return new MetricDefinition(
            tenantId,
            rows.getString("metric_key"),
            rows.getString("display_name"),
            rows.getString("unit"),
            MetricValueType.valueOf(rows.getString("value_type")),
            MetricType.valueOf(rows.getString("metric_type")),
            json.readValue(rows.getString("dimension_schema"), NAMES),
            rows.getLong("version")
        );
    }

    private MetricBinding readBinding(TenantId tenantId, ResultSet rows) throws SQLException {
        return new MetricBinding(
            tenantId,
            rows.getString("source_type"),
            rows.getString("source_instance_id"),
            rows.getString("external_item_id"),
            EntityId.parse(rows.getString("entity_id")),
            rows.getString("host_external_id"),
            rows.getString("metric_key"),
            json.readValue(rows.getString("fixed_dimensions"), MAP),
            rows.getString("source_unit"),
            rows.getString("value_transform"),
            rows.getInt("mapping_revision"),
            MetricLifecycle.valueOf(rows.getString("lifecycle")),
            rows.getLong("version")
        );
    }
}

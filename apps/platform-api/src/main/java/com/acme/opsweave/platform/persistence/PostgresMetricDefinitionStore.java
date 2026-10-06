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

final class PostgresMetricDefinitionStore implements MetricDefinitionStore, SourceItemWritePort, com.acme.opsweave.telemetry.api.MetricMappingStore {
    private static final TypeReference<List<String>> NAMES = new TypeReference<>() {};
    private static final TypeReference<LinkedHashMap<String, String>> MAP = new TypeReference<>() {};
    private final DataSource dataSource;
    private final JsonMapper json = JsonMapper.builder().build();

    PostgresMetricDefinitionStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void upsert(MetricDefinition definition) {
        Transactions.run(dataSource, connection -> {lockTenant(connection,definition.tenantId());upsertDefinition(connection, definition);});
    }

    @Override
    public void upsert(MetricBinding binding) {
        Transactions.run(dataSource, connection -> {lockTenant(connection,binding.tenantId());upsertBinding(connection, binding);});
    }

    @Override
    public void upsert(SourceScan.Token scan, MetricDefinition definition, MetricBinding binding) {
        Transactions.run(dataSource, connection -> {
            lockTenant(connection,binding.tenantId());
            var lease = PostgresSourceScans.require(connection, scan);
            MetricBinding.requireRefreshCompatible(readBindings(connection,binding.tenantId(),binding.sourceInstanceId(),binding.externalItemId()).stream().findFirst().orElse(null),binding);
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
            lockTenant(connection,scan.scope().tenantId());
            var lease = PostgresSourceScans.require(connection, scan);
            retired[0] = retireInside(connection, scan.scope().tenantId(), scan.scope().sourceInstanceId(), observedExternalIds);
            if (Thread.currentThread().isInterrupted()) throw new SourceScan.Failure(SourceScan.Code.DEADLINE);
            lease.require(scan, PostgresSourceScans.now(connection));
            PostgresSourceScans.write(connection, lease.renew(scan, PostgresSourceScans.now(connection)));
        });
        return retired[0];
    }

    @Override
    public int retireMissing(SourceScan.Token scan, Set<String> capturedHostExternalIds, Set<String> observedExternalIds) {
        SourceItemWritePort.requireItemScan(scan);
        Set<String> hosts = SourceItemWritePort.checkedExternalIds(capturedHostExternalIds, "capturedHostExternalIds", false);
        Set<String> observed = SourceItemWritePort.checkedExternalIds(observedExternalIds, "observedExternalIds", true);
        int[] retired = {0};
        Transactions.run(dataSource, connection -> {
            lockTenant(connection, scan.scope().tenantId());
            var lease = PostgresSourceScans.require(connection, scan);
            retired[0] = retireCohortInside(connection, scan.scope().tenantId(), scan.scope().sourceInstanceId(), hosts, observed);
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
            {lockTenant(connection,tenantId);retired[0] = retireInside(connection, tenantId, sourceInstanceId, observedExternalIds);});
        return retired[0];
    }

    private void upsertDefinition(Connection connection, MetricDefinition definition) throws SQLException {
        requireDefinitionCompatible(connection,definition);
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
        var previous=readBindings(connection,binding.tenantId(),binding.sourceInstanceId(),binding.externalItemId()).stream().findFirst().orElse(null);
        MetricBinding.requireRefreshCompatible(previous,binding);
        String dimensions = json.writeValueAsString(binding.fixedDimensions());
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO telemetry.metric_binding (
                tenant_id, source_instance_id, external_item_id, source_type, entity_id, host_external_id,
                metric_key, fixed_dimensions, source_unit, value_transform, mapping_revision, lifecycle, version, mapping_pin
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb)
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
            WHERE (telemetry.metric_binding.source_type,telemetry.metric_binding.entity_id,telemetry.metric_binding.host_external_id,
                telemetry.metric_binding.metric_key,telemetry.metric_binding.fixed_dimensions,telemetry.metric_binding.source_unit,
                telemetry.metric_binding.value_transform,telemetry.metric_binding.mapping_revision,telemetry.metric_binding.lifecycle)
            IS DISTINCT FROM (EXCLUDED.source_type,EXCLUDED.entity_id,EXCLUDED.host_external_id,EXCLUDED.metric_key,
                EXCLUDED.fixed_dimensions,EXCLUDED.source_unit,EXCLUDED.value_transform,EXCLUDED.mapping_revision,EXCLUDED.lifecycle)
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
            statement.setString(14,binding.mappingPin()==null?null:json.writeValueAsString(com.acme.opsweave.platform.telemetry.MetricMappingJson.pin(binding.mappingPin())));
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

    private static int retireCohortInside(
        Connection connection,
        TenantId tenantId,
        String sourceInstanceId,
        Set<String> capturedHostExternalIds,
        Set<String> observedExternalIds
    ) throws SQLException {
        Array hosts = connection.createArrayOf("varchar", capturedHostExternalIds.toArray(String[]::new));
        Array observed = connection.createArrayOf("varchar", observedExternalIds.toArray(String[]::new));
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE telemetry.metric_binding
            SET lifecycle = 'INACTIVE', version = version + 1
            WHERE tenant_id = ? AND source_instance_id = ? AND lifecycle = 'ACTIVE'
              AND host_external_id = ANY (?)
              AND NOT (external_item_id = ANY (?))
            """)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, sourceInstanceId);
            statement.setArray(3, hosts);
            statement.setArray(4, observed);
            return statement.executeUpdate();
        } finally {
            hosts.free();
            observed.free();
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
                       fixed_dimensions, source_unit, value_transform, mapping_revision, lifecycle, version, mapping_pin
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
                       fixed_dimensions, source_unit, value_transform, mapping_revision, lifecycle, version, mapping_pin
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
            rows.getLong("version"),
            rows.getString("mapping_pin")==null?null:com.acme.opsweave.platform.telemetry.MetricMappingJson.pin(json.readTree(rows.getString("mapping_pin")))
        );
    }

    private static void lockTenant(Connection c,TenantId tenant)throws SQLException {
        try(var statement=c.createStatement()){statement.execute("SET LOCAL lock_timeout='5s'");statement.execute("SET LOCAL statement_timeout='10s'");}
        try(var statement=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended('opsweave-metric-mapping:' || ?,0))")){
            statement.setString(1,tenant.value());statement.execute();
        }
    }
    private List<MetricBinding> readBindings(Connection c,TenantId tenant,String source,String item)throws SQLException {
        var result=new ArrayList<MetricBinding>();
        String where=source==null?" ORDER BY source_instance_id,external_item_id LIMIT 201":" AND source_instance_id=? AND external_item_id=?";
        try(var statement=c.prepareStatement("SELECT * FROM telemetry.metric_binding WHERE tenant_id=?"+where)){
            statement.setString(1,tenant.value());if(source!=null){statement.setString(2,source);statement.setString(3,item);}
            try(var rows=statement.executeQuery()){while(rows.next())result.add(readBinding(tenant,rows));}
        }
        return List.copyOf(result);
    }
    private Optional<MetricDefinition> readDefinition(Connection c,TenantId tenant,String key)throws SQLException {
        try(var statement=c.prepareStatement("SELECT * FROM telemetry.metric_definition WHERE tenant_id=? AND metric_key=?")){
            statement.setString(1,tenant.value());statement.setString(2,key);try(var rows=statement.executeQuery()){return rows.next()?Optional.of(readDefinition(tenant,rows)):Optional.empty();}
        }
    }
    private void requireDefinitionCompatible(Connection c,MetricDefinition incoming)throws SQLException {
        var old=readDefinition(c,incoming.tenantId(),incoming.metricKey()).orElse(null);
        if(old==null || old.unit().equals(incoming.unit())&&old.valueType()==incoming.valueType()&&old.metricType()==incoming.metricType()
            && old.dimensionSchema().equals(incoming.dimensionSchema()))return;
        try(var statement=c.prepareStatement("SELECT 1 FROM telemetry.metric_binding WHERE tenant_id=? AND metric_key=? AND mapping_pin IS NOT NULL LIMIT 1")){
            statement.setString(1,incoming.tenantId().value());statement.setString(2,incoming.metricKey());
            try(var rows=statement.executeQuery()){if(rows.next())throw new IllegalStateException("Pinned metric definition semantics changed");}
        }
    }
    private List<MetricBinding> readScopedBindings(Connection c,TenantId tenant,com.acme.opsweave.identity.domain.ResourceScope scope)throws SQLException {
        var predicates=new ArrayList<String>();var values=new ArrayList<List<String>>();
        var columns=new LinkedHashMap<String,String>();columns.put("source","source_instance_id");columns.put("metric","metric_key");columns.put("entity","entity_id");
        for(var field:columns.entrySet()) {
            if(scope.isTenantWide()||scope.includes(new com.acme.opsweave.identity.domain.ResourceRef(tenant,field.getKey(),"*")))continue;
            var ids=scope.allowedResources().stream().filter(ref->ref.tenantId().equals(tenant)&&ref.type().equals(field.getKey())).map(com.acme.opsweave.identity.domain.ResourceRef::id).toList();
            if(ids.isEmpty())return List.of();if(ids.size()>1000)throw new IllegalStateException("Mapping read scope exceeds capacity");
            predicates.add(field.getValue()+"=ANY(?)");values.add(ids);
        }
        String filter=predicates.isEmpty()?"":" AND "+String.join(" AND ",predicates);
        var result=new ArrayList<MetricBinding>();
        try(var statement=c.prepareStatement("SELECT * FROM telemetry.metric_binding WHERE tenant_id=?"+filter+" ORDER BY source_instance_id,external_item_id LIMIT 201")){
            statement.setString(1,tenant.value());int i=2;for(var ids:values)statement.setArray(i++,c.createArrayOf("varchar",ids.toArray(String[]::new)));
            try(var rows=statement.executeQuery()){while(rows.next())result.add(readBinding(tenant,rows));}
        }
        return List.copyOf(result);
    }
    private interface SqlValue<T>{T get()throws SQLException;}
    private static <T> T sql(SqlValue<T> read){try{return read.get();}catch(SQLException e){throw new IllegalStateException("Metric mapping store unavailable");}}
    @Override public <T> T mappingTransaction(TenantId tenant,java.util.function.Function<Session,T> work){
        var result=new java.util.concurrent.atomic.AtomicReference<T>();
        Transactions.run(dataSource,c->{lockTenant(c,tenant);result.set(work.apply(new Session(){
            public Optional<MetricBinding> binding(String source,String item){return sql(()->readBindings(c,tenant,source,item).stream().findFirst());}
            public Optional<MetricDefinition> definition(String key){return sql(()->readDefinition(c,tenant,key));}
            public List<MetricBinding> bindings(com.acme.opsweave.identity.domain.ResourceScope scope){return sql(()->readScopedBindings(c,tenant,scope));}
            public Optional<com.acme.opsweave.telemetry.domain.MetricMappingReceipt> receipt(String owner,java.util.UUID id){
                return sql(()->{try(var statement=c.prepareStatement("SELECT source_instance_id,external_item_id,body::text FROM telemetry.metric_mapping_command WHERE tenant_id=? AND owner_id=? AND request_id=?")){
                    statement.setString(1,tenant.value());statement.setString(2,owner);statement.setObject(3,id);
                    try(var rows=statement.executeQuery()){
                        if(!rows.next())return Optional.empty();
                        var r=com.acme.opsweave.platform.telemetry.MetricMappingJson.receipt(tenant,json.readTree(rows.getString(3)));
                        var command=new com.acme.opsweave.integration.application.MetricMappingService.Command(r.requestId(),r.expectedBindingVersion(),r.binding().mappingPin());
                        if(!r.requestId().equals(id)||!r.binding().sourceInstanceId().equals(rows.getString(1))||!r.binding().externalItemId().equals(rows.getString(2))
                            ||!r.commandDigest().equals(com.acme.opsweave.integration.application.MetricMappingService.digest(rows.getString(1),rows.getString(2),command)))
                            throw new IllegalStateException("Metric mapping receipt invalid");
                        return Optional.of(r);
                    }
                }});
            }
            public int receiptCount(String owner){return sql(()->{
                try(var statement=c.prepareStatement("SELECT count(*) FROM telemetry.metric_mapping_command WHERE tenant_id=? AND owner_id=?")){
                    statement.setString(1,tenant.value());statement.setString(2,owner);try(var rows=statement.executeQuery()){rows.next();return rows.getInt(1);}
                }});}
            public void replace(MetricBinding next,long expected){sql(()->{
                if(!next.tenantId().equals(tenant))throw new IllegalArgumentException();
                try(var statement=c.prepareStatement("UPDATE telemetry.metric_binding SET fixed_dimensions=?::jsonb,value_transform=?,mapping_revision=?,mapping_pin=?::jsonb,version=? WHERE tenant_id=? AND source_instance_id=? AND external_item_id=? AND version=?")){
                    statement.setString(1,json.writeValueAsString(next.fixedDimensions()));statement.setString(2,next.valueTransform());statement.setInt(3,next.mappingRevision());
                    statement.setString(4,json.writeValueAsString(com.acme.opsweave.platform.telemetry.MetricMappingJson.pin(next.mappingPin())));statement.setLong(5,next.version());
                    statement.setString(6,tenant.value());statement.setString(7,next.sourceInstanceId());statement.setString(8,next.externalItemId());statement.setLong(9,expected);
                    if(statement.executeUpdate()!=1)throw new com.acme.opsweave.telemetry.domain.MetricMappingFailure(com.acme.opsweave.telemetry.domain.MetricMappingFailure.Code.CONFLICT);
                }return null;});}
            public void addReceipt(String owner,com.acme.opsweave.telemetry.domain.MetricMappingReceipt r){sql(()->{
                if(!r.binding().tenantId().equals(tenant))throw new IllegalArgumentException();
                try(var statement=c.prepareStatement("INSERT INTO telemetry.metric_mapping_command(tenant_id,owner_id,request_id,source_instance_id,external_item_id,body) VALUES(?,?,?,?,?,?::jsonb)")){
                    statement.setString(1,tenant.value());statement.setString(2,owner);statement.setObject(3,r.requestId());statement.setString(4,r.binding().sourceInstanceId());statement.setString(5,r.binding().externalItemId());
                    statement.setString(6,json.writeValueAsString(com.acme.opsweave.platform.telemetry.MetricMappingJson.receipt(r)));statement.executeUpdate();
                }return null;});}
        }));});
        return result.get();
    }
}

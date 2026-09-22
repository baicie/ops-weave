package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.integration.api.SyncRunStore;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.inventory.api.InventoryQuery;
import com.acme.opsweave.inventory.api.InventoryWritePort;
import com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore;
import com.acme.opsweave.integration.infrastructure.InMemoryRawRecordStore;
import com.acme.opsweave.integration.infrastructure.InMemorySyncRunStore;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.telemetry.api.MetricDefinitionStore;
import com.acme.opsweave.telemetry.infrastructure.InMemoryMetricDefinitionStore;
import com.zaxxer.hikari.HikariDataSource;

public final class InventoryWiring {
    private final InventoryQuery query;
    private final InventoryWritePort writer;
    private final IngestZabbixHostsUseCase.RawRecordCollector rawRecords;
    private final SyncRunStore syncRuns;
    private final MetricDefinitionStore metrics;
    private final String label;

    private InventoryWiring(
        InventoryQuery query,
        InventoryWritePort writer,
        IngestZabbixHostsUseCase.RawRecordCollector rawRecords,
        SyncRunStore syncRuns,
        MetricDefinitionStore metrics,
        String label
    ) {
        this.query = query;
        this.writer = writer;
        this.rawRecords = rawRecords;
        this.syncRuns = syncRuns;
        this.metrics = metrics;
        this.label = label;
    }

    public static InventoryWiring open(OpsweaveProperties properties) {
        String store = properties.inventory().store() == null ? "postgres" : properties.inventory().store().trim();
        if ("memory".equalsIgnoreCase(store)) {
            InMemoryInventoryStore inventory = new InMemoryInventoryStore();
            return new InventoryWiring(inventory, inventory, new InMemoryRawRecordStore(), new InMemorySyncRunStore(), new InMemoryMetricDefinitionStore(), "memory");
        }
        if (!"postgres".equalsIgnoreCase(store)) {
            throw new IllegalStateException("Unknown inventory store");
        }
        OpsweaveProperties.Inventory inventory = properties.inventory();
        if (inventory.jdbcUrl() == null || inventory.jdbcUrl().isBlank()
            || inventory.jdbcUser() == null || inventory.jdbcUser().isBlank()) {
            throw new IllegalStateException("OPSWEAVE_JDBC_URL and OPSWEAVE_JDBC_USER are required for the postgres inventory store");
        }
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(inventory.jdbcUrl());
        dataSource.setUsername(inventory.jdbcUser());
        dataSource.setPassword(inventory.jdbcPassword() == null ? "" : inventory.jdbcPassword());
        dataSource.setMaximumPoolSize(4);
        new SchemaMigrator(dataSource).apply("db/migration/V002__host_sync.sql", "V002__host_sync");
        new SchemaMigrator(dataSource).apply("db/migration/V003__metric_definition.sql", "V003__metric_definition");
        PostgresInventoryStore postgres = new PostgresInventoryStore(dataSource);
        PostgresSyncStore sync = new PostgresSyncStore(dataSource);
        return new InventoryWiring(postgres, postgres, sync, sync, new PostgresMetricDefinitionStore(dataSource), "postgres");
    }

    public InventoryQuery query() {
        return query;
    }

    public InventoryWritePort writer() {
        return writer;
    }

    public IngestZabbixHostsUseCase.RawRecordCollector rawRecords() {
        return rawRecords;
    }

    public SyncRunStore syncRuns() {
        return syncRuns;
    }

    public MetricDefinitionStore metrics() {
        return metrics;
    }

    public String label() {
        return label;
    }
}

package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.integration.api.SyncRunStore;
import com.acme.opsweave.integration.api.SourceItemWritePort;
import com.acme.opsweave.integration.api.SourceConnectionCheckStore;
import com.acme.opsweave.integration.api.RawRecordReader;
import com.acme.opsweave.integration.api.PipelineVersionStore;
import com.acme.opsweave.integration.api.PipelineReplayStore;
import com.acme.opsweave.integration.api.PipelineDraftStore;
import com.acme.opsweave.integration.infrastructure.InMemoryPipelineDraftStore;
import com.acme.opsweave.integration.infrastructure.InMemoryPipelineReplayStore;
import com.acme.opsweave.integration.infrastructure.InMemoryPipelineVersionStore;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.inventory.api.InventoryQuery;
import com.acme.opsweave.inventory.api.InventoryWritePort;
import com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore;
import com.acme.opsweave.integration.infrastructure.InMemoryRawRecordStore;
import com.acme.opsweave.integration.infrastructure.InMemorySyncRunStore;
import com.acme.opsweave.integration.infrastructure.InMemorySourceItemWrites;
import com.acme.opsweave.integration.infrastructure.InMemorySourceConnectionCheckStore;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.telemetry.api.MetricDefinitionStore;
import com.acme.opsweave.telemetry.infrastructure.InMemoryMetricDefinitionStore;
import com.zaxxer.hikari.HikariDataSource;

public final class InventoryWiring implements AutoCloseable {
    private final com.acme.opsweave.integration.api.WorkflowStore memoryWorkflows = new com.acme.opsweave.integration.infrastructure.InMemoryWorkflowStore();
    public com.acme.opsweave.integration.api.WorkflowStore workflows() { return ownedDataSource == null ? memoryWorkflows : new PostgresWorkflowStore(ownedDataSource); }
    private final com.acme.opsweave.catalog.api.ModelCatalogStore memoryCatalog = new com.acme.opsweave.catalog.infrastructure.InMemoryModelCatalogStore();
    private HikariDataSource ownedDataSource;
    private com.acme.opsweave.inventory.api.SourceSnapshotStore sourceSnapshotsWired;
    private com.acme.opsweave.inventory.api.SourceReviewStore sourceReviewsWired;
    private com.acme.opsweave.inventory.api.SourceSnapshotStore rawSnapshots;
    private final InventoryQuery query;
    private final InventoryWritePort writer;
    private final com.acme.opsweave.inventory.api.EntityInstanceStore entityInstances;
    private final com.acme.opsweave.inventory.api.RelationStore relations;
    private final IngestZabbixHostsUseCase.RawRecordCollector rawRecords;
    private final SyncRunStore syncRuns;
    private final MetricDefinitionStore metrics;
    private final SourceItemWritePort itemWrites;
    private final SourceConnectionCheckStore sourceChecks;
    private final com.acme.opsweave.inventory.api.RejectedWriteAttemptStore rejectedWrites;
    private final String cmdbImportSource;
    private final String label;
    private final RawRecordReader rawReader;
    private final PipelineVersionStore pipelines;
    private final PipelineReplayStore replays;
    private final PipelineDraftStore drafts;
    private final com.acme.opsweave.incident.api.IncidentStore incidents;
    private final com.acme.opsweave.aicontrol.api.ToolReadStore toolReads;
    private final com.acme.opsweave.aicontrol.api.AiInsightStore insights;
    private final com.acme.opsweave.aicontrol.api.ModelSpendStore memorySpend = new com.acme.opsweave.aicontrol.infrastructure.InMemoryModelSpendStore();

    private InventoryWiring(
        InventoryQuery query,
        InventoryWritePort writer,
        com.acme.opsweave.inventory.api.EntityInstanceStore entityInstances,
        com.acme.opsweave.inventory.api.RelationStore relations,
        IngestZabbixHostsUseCase.RawRecordCollector rawRecords,
        SyncRunStore syncRuns,
        MetricDefinitionStore metrics,
        SourceItemWritePort itemWrites,
        SourceConnectionCheckStore sourceChecks,
        com.acme.opsweave.inventory.api.RejectedWriteAttemptStore rejectedWrites,
        String cmdbImportSource,
        com.acme.opsweave.inventory.api.SourceSnapshotStore sourceSnapshotsWired,
        com.acme.opsweave.inventory.api.SourceSnapshotStore rawSnapshots,
        com.acme.opsweave.inventory.api.SourceReviewStore sourceReviewsWired,
        String label,
        RawRecordReader rawReader,
        PipelineVersionStore pipelines,
        PipelineReplayStore replays,
        PipelineDraftStore drafts,
        com.acme.opsweave.incident.api.IncidentStore incidents,
        com.acme.opsweave.aicontrol.api.ToolReadStore toolReads,
        com.acme.opsweave.aicontrol.api.AiInsightStore insights
    ) {
        this.query = query;
        this.writer = writer;
        this.entityInstances = entityInstances;
        this.relations = relations;
        this.rawRecords = rawRecords;
        this.syncRuns = syncRuns;
        this.metrics = metrics;
        this.itemWrites = itemWrites;
        this.sourceChecks = sourceChecks;
        this.rejectedWrites = rejectedWrites;
        this.cmdbImportSource = cmdbImportSource == null ? "" : cmdbImportSource;
        this.sourceSnapshotsWired = sourceSnapshotsWired;
        this.rawSnapshots = rawSnapshots;
        this.sourceReviewsWired = sourceReviewsWired;
        this.label = label;
        this.rawReader = rawReader;
        this.pipelines = pipelines;
        this.replays = replays;
        this.drafts = drafts;
        this.incidents = incidents;
        this.toolReads = toolReads;
        this.insights = insights;
    }

    public static InventoryWiring open(OpsweaveProperties properties, String cmdbImportSource) {
        String store = properties.inventory().store() == null ? "postgres" : properties.inventory().store().trim();
        if ("memory".equalsIgnoreCase(store)) {
            InMemoryInventoryStore inventory = new InMemoryInventoryStore();
            var raw = new InMemoryRawRecordStore();
            var metrics = new InMemoryMetricDefinitionStore();
            var incidents = new com.acme.opsweave.incident.infrastructure.InMemoryIncidentStore((tenant, source, hosts) -> {
                var result = new java.util.HashMap<String, com.acme.opsweave.sharedkernel.EntityId>();
                for (String host : hosts) inventory.linkOf(new com.acme.opsweave.inventory.domain.ExternalObjectKey(tenant, source, "host", host, "1")).ifPresent(link -> result.put(host, link.entityId()));
                return result;
            });
            var tools = new com.acme.opsweave.aicontrol.infrastructure.InMemoryToolReadStore();
            var rejected = new com.acme.opsweave.inventory.infrastructure.InMemoryRejectedWriteAttemptStore(
                properties.rejectedWriteRetention(null));
            var sourceReviews = com.acme.opsweave.inventory.infrastructure.AuditedSourceStores.reviews(
                inventory, rejected, java.time.Clock.systemUTC(), cmdbImportSource);
            return new InventoryWiring(inventory, inventory, inventory, new com.acme.opsweave.inventory.infrastructure.InMemoryRelationStore(), raw,
                new InMemorySyncRunStore(properties.scanRunRetention(null, null)), metrics, new InMemorySourceItemWrites(inventory, metrics), new InMemorySourceConnectionCheckStore(), rejected, cmdbImportSource, null, null, sourceReviews, "memory", raw, new InMemoryPipelineVersionStore(), new InMemoryPipelineReplayStore(), new InMemoryPipelineDraftStore(), incidents, tools,
                new com.acme.opsweave.aicontrol.infrastructure.InMemoryAiInsightStore(incidents, tools, java.time.Clock.systemUTC()));
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
        dataSource.setConnectionTimeout(5000);
        try {
        new SchemaMigrator(dataSource).apply("db/migration/V002__host_sync.sql", "V002__host_sync");
        new SchemaMigrator(dataSource).apply("db/migration/V003__metric_definition.sql", "V003__metric_definition");
        new SchemaMigrator(dataSource).apply("db/migration/V004__metric_catalog.sql", "V004__metric_catalog");
        new SchemaMigrator(dataSource).apply("db/migration/V007__pipeline_version.sql", "V007__pipeline_version");
        new SchemaMigrator(dataSource).apply("db/migration/V008__pipeline_replay.sql", "V008__pipeline_replay");
        new SchemaMigrator(dataSource).apply("db/migration/V009__pipeline_draft.sql", "V009__pipeline_draft");
        new SchemaMigrator(dataSource).apply("db/migration/V010__external_problem_incident.sql", "V010__external_problem_incident");
        new SchemaMigrator(dataSource).apply("db/migration/V011__tool_read_evidence.sql", "V011__tool_read_evidence");
        new SchemaMigrator(dataSource).apply("db/migration/V012__ai_insight.sql", "V012__ai_insight");
        new SchemaMigrator(dataSource).apply("db/migration/V013__incident_reorganization.sql", "V013__incident_reorganization");
        new SchemaMigrator(dataSource).apply("db/migration/V014__observation_history.sql", "V014__observation_history");
        new SchemaMigrator(dataSource).apply("db/migration/V015__source_review.sql", "V015__source_review");
        new SchemaMigrator(dataSource).apply("db/migration/V016__problem_observation.sql", "V016__problem_observation");
        new SchemaMigrator(dataSource).apply("db/migration/V017__asset_identity.sql", "V017__asset_identity");
        new SchemaMigrator(dataSource).apply("db/migration/V018__model_spend.sql", "V018__model_spend");
        new SchemaMigrator(dataSource).apply("db/migration/V019__ai_content_retention.sql", "V019__ai_content_retention");
        new SchemaMigrator(dataSource).apply("db/migration/V020__inventory_source_scan.sql", "V020__inventory_source_scan");
        new SchemaMigrator(dataSource).apply("db/migration/V021__source_snapshot.sql", "V021__source_snapshot");
        new SchemaMigrator(dataSource).apply("db/migration/V022__source_binding_correction.sql", "V022__source_binding_correction");
        new SchemaMigrator(dataSource).apply("db/migration/V023__scan_run_trace.sql", "V023__scan_run_trace");
        new SchemaMigrator(dataSource).apply("db/migration/V024__scan_run_consistency.sql", "V024__scan_run_consistency");
        new SchemaMigrator(dataSource).apply("db/migration/V025__item_watermark_label.sql", "V025__item_watermark_label");
        new SchemaMigrator(dataSource).apply("db/migration/V026__source_connection_check.sql", "V026__source_connection_check");
        new SchemaMigrator(dataSource).apply("db/migration/V027__rejected_write_audit.sql", "V027__rejected_write_audit");
        new SchemaMigrator(dataSource).apply("db/migration/V028__model_catalog.sql", "V028__model_catalog");
        new SchemaMigrator(dataSource).apply("db/migration/V029__transform_workflow.sql", "V029__transform_workflow");
        new SchemaMigrator(dataSource).apply("db/migration/V030__source_setup.sql", "V030__source_setup");
        new SchemaMigrator(dataSource).apply("db/migration/V031__entity_relation_read.sql", "V031__entity_relation_read");
        new SchemaMigrator(dataSource).apply("db/migration/V032__workflow_runtime.sql", "V032__workflow_runtime");
        new SchemaMigrator(dataSource).apply("db/migration/V033__workflow_graph_trace_budget.sql", "V033__workflow_graph_trace_budget");
        new SchemaMigrator(dataSource).apply("db/migration/V034__workflow_batch_cursor.sql", "V034__workflow_batch_cursor");
        new SchemaMigrator(dataSource).apply("db/migration/V035__source_instance_versions.sql", "V035__source_instance_versions");
        new SchemaMigrator(dataSource).apply("db/migration/V036__source_inspection.sql", "V036__source_inspection");
        new SchemaMigrator(dataSource).apply("db/migration/V037__source_credentials.sql", "V037__source_credentials");
        new SchemaMigrator(dataSource).apply("db/migration/V038__source_connection_configuration.sql", "V038__source_connection_configuration");
        new SchemaMigrator(dataSource).apply("db/migration/V039__source_metric_metadata_budget.sql", "V039__source_metric_metadata_budget");
        new SchemaMigrator(dataSource).apply("db/migration/V040__metric_mapping_pin.sql", "V040__metric_mapping_pin");
        new SchemaMigrator(dataSource).apply("db/migration/V041__workflow_control_receipts.sql", "V041__workflow_control_receipts");
        new SchemaMigrator(dataSource).apply("db/migration/V042__workflow_metric_output.sql", "V042__workflow_metric_output");
        new SchemaMigrator(dataSource).apply("db/migration/V043__workflow_host_batches.sql", "V043__workflow_host_batches");
        new SchemaMigrator(dataSource).apply("db/migration/V044__workflow_metric_stream.sql", "V044__workflow_metric_stream");
        new SchemaMigrator(dataSource).apply("db/migration/V045__workflow_host_schedule.sql", "V045__workflow_host_schedule");
        new SchemaMigrator(dataSource).apply("db/migration/V046__workflow_log_output.sql", "V046__workflow_log_output");
        new SchemaMigrator(dataSource).apply("db/migration/V047__metric_window_reconciliation.sql", "V047__metric_window_reconciliation");
        new SchemaMigrator(dataSource).apply("db/migration/V048__workflow_log_stream.sql", "V048__workflow_log_stream");
        new SchemaMigrator(dataSource).apply("db/migration/V049__workflow_diagnostics.sql", "V049__workflow_diagnostics");
        new SchemaMigrator(dataSource).apply("db/migration/V050__workflow_recovery_closure.sql", "V050__workflow_recovery_closure");
        new SchemaMigrator(dataSource).apply("db/migration/V051__workflow_task_archive.sql", "V051__workflow_task_archive");
        new SchemaMigrator(dataSource).apply("db/migration/V052__log_rejection_certainty.sql", "V052__log_rejection_certainty");
        new SchemaMigrator(dataSource).apply("db/migration/V053__workflow_sample_recovery.sql", "V053__workflow_sample_recovery");
        new SchemaMigrator(dataSource).apply("db/migration/V054__workflow_quality_alerts.sql", "V054__workflow_quality_alerts");
        new SchemaMigrator(dataSource).apply("db/migration/V055__workflow_history_selection.sql", "V055__workflow_history_selection");
        new SchemaMigrator(dataSource).apply("db/migration/V056__workflow_metric_replay.sql", "V056__workflow_metric_replay");
        new SchemaMigrator(dataSource).apply("db/migration/V057__workflow_log_replay.sql", "V057__workflow_log_replay");
        new SchemaMigrator(dataSource).apply("db/migration/V058__entity_relation_write.sql", "V058__entity_relation_write");
        new SchemaMigrator(dataSource).apply("db/migration/V059__entity_instance_write.sql", "V059__entity_instance_write");
        new SchemaMigrator(dataSource).apply("db/migration/V060__entity_model_pin.sql", "V060__entity_model_pin");
        PostgresInventoryStore postgres = new PostgresInventoryStore(dataSource);
        PostgresSyncStore sync = new PostgresSyncStore(dataSource, properties.scanRunRetention(null, null));
        var metrics = new PostgresMetricDefinitionStore(dataSource);
        var rejected = new PostgresRejectedWriteAttempts(dataSource, properties.rejectedWriteRetention(null));
        var sourceReviews = com.acme.opsweave.inventory.infrastructure.AuditedSourceStores.reviews(
            new PostgresSourceReviews(dataSource), rejected, java.time.Clock.systemUTC(), cmdbImportSource);
        var rawSnapshots = new PostgresSourceSnapshots(dataSource);
        var sourceSnapshots = com.acme.opsweave.inventory.infrastructure.AuditedSourceStores.snapshots(
            rawSnapshots, rejected, java.time.Clock.systemUTC(), cmdbImportSource);
        var wiring = new InventoryWiring(postgres, postgres, new PostgresEntityInstanceStore(dataSource), new PostgresRelationStore(dataSource), sync, sync, metrics, metrics, new PostgresSourceConnectionChecks(dataSource), rejected, cmdbImportSource, sourceSnapshots, rawSnapshots, sourceReviews, "postgres", sync, new PostgresPipelineVersionStore(dataSource), new PostgresPipelineReplayStore(dataSource), new PostgresPipelineDraftStore(dataSource), new PostgresIncidentStore(dataSource), new PostgresToolReadStore(dataSource), new PostgresAiInsightStore(dataSource, java.time.Clock.systemUTC()));
        wiring.ownedDataSource = dataSource; return wiring;
        } catch (RuntimeException failed) { dataSource.close(); throw failed; }
    }

    public com.acme.opsweave.inventory.api.TopologyReader topology() {
        if (ownedDataSource == null) return (tenant,center,scope,at) -> { throw new IllegalStateException("Topology requires PostgreSQL"); };
        return new PostgresTopologyReader(ownedDataSource);
    }

    public InventoryQuery query() {
        return query;
    }

    public InventoryWritePort writer() {
        return writer;
    }
    public com.acme.opsweave.inventory.api.EntityInstanceStore entityInstances() { return entityInstances; }
    public com.acme.opsweave.inventory.api.RelationStore relations() { return relations; }
    public com.acme.opsweave.inventory.api.ObservationReader observations() { return (com.acme.opsweave.inventory.api.ObservationReader) writer; }

    public IngestZabbixHostsUseCase.RawRecordCollector rawRecords() {
        return rawRecords;
    }

    public SyncRunStore syncRuns() {
        return syncRuns;
    }

    public com.acme.opsweave.telemetry.api.MetricMappingStore metricMappings() {
        return (com.acme.opsweave.telemetry.api.MetricMappingStore) metrics;
    }
    public MetricDefinitionStore metrics() {
        return metrics;
    }

    public SourceItemWritePort itemWrites() {
        return itemWrites;
    }

    public SourceConnectionCheckStore sourceChecks() {
        return sourceChecks;
    }

    public com.acme.opsweave.inventory.api.RejectedWriteAttemptStore rejectedWrites() { return rejectedWrites; }

    public com.acme.opsweave.inventory.api.SourceReviewStore sourceReviews() {
        return sourceReviewsWired != null ? sourceReviewsWired : (com.acme.opsweave.inventory.api.SourceReviewStore) writer;
    }
    public com.acme.opsweave.inventory.api.AssetIdentityStore assetIdentities() {
        return ownedDataSource == null ? (com.acme.opsweave.inventory.api.AssetIdentityStore) writer : new PostgresAssetIdentities(ownedDataSource);
    }
    public com.acme.opsweave.inventory.api.SourceSnapshotStore sourceSnapshots() {
        if (sourceSnapshotsWired != null) {
            return sourceSnapshotsWired;
        }
        if (ownedDataSource == null) throw new IllegalStateException("Persistent source snapshots require PostgreSQL");
        return new PostgresSourceSnapshots(ownedDataSource);
    }

    public String label() {
        return label;
    }

    /** The configured supplemental import source; the refusal log is scoped to it. */
    public String cmdbImportSource() {
        return cmdbImportSource;
    }

    /**
     * Counts the stored receipts of the configured source. The audited snapshot store decorates the
     * same underlying store, so counting here describes exactly what the caps protect.
     */
    public com.acme.opsweave.inventory.api.SourceReceiptCapacityReader receiptCapacity() {
        if (rawSnapshots == null) {
            return EMPTY_RECEIPTS;
        }
        if (rawSnapshots instanceof com.acme.opsweave.inventory.api.SourceReceiptCapacityReader receipts) {
            return receipts;
        }
        throw new IllegalStateException("Receipt capacity is unavailable for this store");
    }

    /** The in-memory build stores no snapshot or correction receipts, so it reports zero truthfully. */
    private static final com.acme.opsweave.inventory.api.SourceReceiptCapacityReader EMPTY_RECEIPTS =
        new com.acme.opsweave.inventory.api.SourceReceiptCapacityReader() {
            public int snapshotReceipts(com.acme.opsweave.sharedkernel.TenantId tenantId, String sourceInstanceId) { return 0; }
            public int correctionReceipts(com.acme.opsweave.sharedkernel.TenantId tenantId, String sourceInstanceId) { return 0; }
        };
    public RawRecordReader rawReader() { return rawReader; }
    public PipelineVersionStore pipelines() { return pipelines; }
    public PipelineReplayStore replays() { return replays; }
    public PipelineDraftStore drafts() { return drafts; }
    public com.acme.opsweave.incident.api.IncidentStore incidents() { return incidents; }
    public com.acme.opsweave.incident.api.ProblemHistoryReader problemHistory() { return (com.acme.opsweave.incident.api.ProblemHistoryReader) incidents; }
    public com.acme.opsweave.aicontrol.api.ToolReadStore toolReads() { return toolReads; }
    public com.acme.opsweave.aicontrol.api.AiInsightStore insights() { return insights; }
    public com.acme.opsweave.aicontrol.api.ModelSpendStore modelSpend() { return ownedDataSource == null ? memorySpend : new PostgresModelSpendStore(ownedDataSource); }
    public com.acme.opsweave.aicontrol.api.AiRetentionStore retention() {
        if(ownedDataSource==null)throw new com.acme.opsweave.aicontrol.domain.ToolFailure(com.acme.opsweave.aicontrol.domain.ToolFailure.Code.UNAVAILABLE);
        return new PostgresAiRetentionStore(ownedDataSource,java.time.Clock.systemUTC());
    }
    public com.acme.opsweave.catalog.api.ModelCatalogStore modelCatalog() { return ownedDataSource == null ? memoryCatalog : new PostgresModelCatalogStore(ownedDataSource); }
    public void close() { if (ownedDataSource != null) ownedDataSource.close(); }
}

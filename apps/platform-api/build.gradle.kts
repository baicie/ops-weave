import org.gradle.language.jvm.tasks.ProcessResources

plugins { java; id("org.springframework.boot") }
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.8"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    implementation(project(":modules:identity"))
    implementation(project(":modules:catalog"))
    implementation(project(":modules:inventory"))
    implementation(project(":modules:integration"))
    implementation(project(":modules:telemetry"))
    implementation(project(":modules:alerting"))
    implementation(project(":modules:incident"))
    implementation(project(":modules:ai-control"))
    implementation(project(":modules:automation"))
    implementation(project(":modules:audit"))
}

tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("db/migrations/platform/V035__source_instance_versions.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V036__source_inspection.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V037__source_credentials.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V038__source_connection_configuration.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V040__metric_mapping_pin.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V041__workflow_control_receipts.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V042__workflow_metric_output.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V043__workflow_host_batches.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V044__workflow_metric_stream.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V045__workflow_host_schedule.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V046__workflow_log_output.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V047__metric_window_reconciliation.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V048__workflow_log_stream.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V049__workflow_diagnostics.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V050__workflow_recovery_closure.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V051__workflow_task_archive.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V052__log_rejection_certainty.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V053__workflow_sample_recovery.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V054__workflow_quality_alerts.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V055__workflow_history_selection.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V056__workflow_metric_replay.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V057__workflow_log_replay.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V058__entity_relation_write.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V059__entity_instance_write.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V060__entity_model_pin.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V039__source_metric_metadata_budget.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V032__workflow_runtime.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V033__workflow_graph_trace_budget.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V034__workflow_batch_cursor.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V031__entity_relation_read.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V030__source_setup.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V029__transform_workflow.sql")) { into("db/migration") }
    from(rootProject.file("contracts/catalog/opsweave-core-1.0.0.json")) { into("catalog") }
    from(rootProject.file("db/migrations/platform/V028__model_catalog.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V017__asset_identity.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V018__model_spend.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V019__ai_content_retention.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V020__inventory_source_scan.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V021__source_snapshot.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V022__source_binding_correction.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V023__scan_run_trace.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V024__scan_run_consistency.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V025__item_watermark_label.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V026__source_connection_check.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V027__rejected_write_audit.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V016__problem_observation.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V012__ai_insight.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V013__incident_reorganization.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V015__source_review.sql")) { into("db/migration") }
    from(rootProject.file("db/migrations/platform/V014__observation_history.sql")) { into("db/migration") }
    from(rootProject.file("extensions/skills/incident-diagnosis-current")) { into("skills/incident-diagnosis-current") }
    from(rootProject.file("db/migrations/platform/V011__tool_read_evidence.sql")) {
        into("db/migration")
    }
    from(rootProject.file("db/migrations/platform/V010__external_problem_incident.sql")) {
        into("db/migration")
    }
    from(rootProject.file("db/migrations/platform/V009__pipeline_draft.sql")) {
        into("db/migration")
    }
    from(rootProject.file("db/migrations/platform/V008__pipeline_replay.sql")) {
        into("db/migration")
    }
    from(rootProject.file("db/migrations/platform/V007__pipeline_version.sql")) {
        into("db/migration")
    }
    from(rootProject.file("db/migrations/platform/V002__host_sync.sql")) {
        into("db/migration")
    }
    from(rootProject.file("db/migrations/platform/V003__metric_definition.sql")) {
        into("db/migration")
    }
    from(rootProject.file("db/migrations/platform/V004__metric_catalog.sql")) {
        into("db/migration")
    }
    from(rootProject.file("extensions/mappings")) {
        into("mappings")
    }
}

tasks.named<ProcessResources>("processTestResources") {
    from(rootProject.file("contracts/examples/history-service-grants.json")) { into("contracts") }
}

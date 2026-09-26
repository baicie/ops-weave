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

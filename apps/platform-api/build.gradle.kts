import org.gradle.language.jvm.tasks.ProcessResources

plugins { java; id("org.springframework.boot") }
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.8"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
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

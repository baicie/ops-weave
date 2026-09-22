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
    implementation(project(":modules:integration"))
}

tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("db/migrations/ingestion/V001__history_checkpoint.sql")) { into("db/ingestion") }
}

tasks.named<ProcessResources>("processTestResources") {
    from(rootProject.file("contracts/examples/metric-history-page.json")) { into("contracts") }
}

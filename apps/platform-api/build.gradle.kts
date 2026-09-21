plugins { java; id("org.springframework.boot") }
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.8"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
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

plugins { `java-library` }
dependencies {
    api(project(":modules:shared-kernel"))
    api(project(":modules:inventory"))
    api(project(":modules:identity"))
    api(project(":modules:telemetry"))
}

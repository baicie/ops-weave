plugins { `java-library` }
dependencies {
    api(project(":modules:shared-kernel"))
    api(project(":modules:identity"))
    api(project(":modules:alerting"))
}

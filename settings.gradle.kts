pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}
rootProject.name = "opsweave"
include(":modules:shared-kernel")
include(":modules:identity")
include(":modules:catalog")
include(":modules:inventory")
include(":modules:integration")
include(":modules:telemetry")
include(":modules:alerting")
include(":modules:incident")
include(":modules:ai-control")
include(":modules:automation")
include(":modules:audit")
include(":apps:platform-api", ":apps:ingestion-worker")

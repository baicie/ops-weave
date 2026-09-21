import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test

plugins {
    base
    id("org.springframework.boot") version "4.0.8" apply false
}
allprojects {
    group = "com.acme.opsweave"
    version = "0.1.0-SNAPSHOT"
}
subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }
        dependencyLocking { lockAllConfigurations() }
    }
}

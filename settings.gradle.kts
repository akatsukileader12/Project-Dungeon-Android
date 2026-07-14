pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Lets Gradle auto-provision a matching JDK (17) if one isn't already
    // installed (e.g. in CI), instead of failing the build.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "project-dungeon-android"

include(":core")
include(":app")

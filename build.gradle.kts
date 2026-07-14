// Root project: no source of its own. `core` holds the engine-agnostic game
// logic, `app` is the Android application module that runs it.
//
// Plugin versions are declared once here (apply false) and referenced
// without a version in each subproject, so the Kotlin Gradle plugin is only
// loaded once across the whole build.
plugins {
    kotlin("jvm") version "1.9.24" apply false
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

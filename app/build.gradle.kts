plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

repositories {
    google()
    mavenCentral()
}

val jmeVersion = "3.6.1-stable"

android {
    namespace = "com.example.dungeon.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.dungeon.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Native .so libs from jme3-android-native / Minie's "+droid" variant
        // occasionally collide on metadata files -- keep the first copy.
        resources.pickFirsts.add("META-INF/*")
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))

    // Android renderer (OpenGL ES via AndroidHarness) + native support libs
    implementation("org.jmonkeyengine:jme3-android:$jmeVersion")
    implementation("org.jmonkeyengine:jme3-android-native:$jmeVersion")

    // Bullet physics -- Android-native variant (arm64/armeabi/x86/x86_64 .so)
    implementation("com.github.stephengold:Minie:9.0.1+droid")
}

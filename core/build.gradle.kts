plugins {
    kotlin("jvm")
}

repositories {
    google()
    mavenCentral()
}

// Pinned to 3.7.0, not 3.6.1: Minie 9.0.1 transitively pulls jme3-core
// 3.7.0-stable (via Heart/sim-math/jme3-terrain), and Gradle resolves that
// version conflict by picking the higher one project-wide. Declaring
// jme3-core/jme3-android/jme3-android-native at 3.6.1 while Minie forces
// 3.7.0 in means the Android GL wrapper (AndroidGL, compiled against
// 3.6.1's GLES_30 interface) ends up loaded next to a 3.7.0 GLES_30
// interface that added glGenVertexArrays -- AndroidGL never got a chance
// to implement it, so the renderer throws AbstractMethodError the instant
// it initializes. Declaring 3.7.0 everywhere keeps every module compiled
// against the exact interface it runs against.
val jmeVersion = "3.7.0-stable"

dependencies {
    // Platform-agnostic engine pieces only. No renderer and no Bullet
    // natives here -- the app module supplies the Android renderer and
    // Android-native physics libs on its own runtime classpath.
    implementation("org.jmonkeyengine:jme3-core:$jmeVersion")
    implementation("org.jmonkeyengine:jme3-effects:$jmeVersion")

    // glTF 2.0 loader -- lets us load rigged/animated models (e.g. the
    // dragon boss) exported from Blender, instead of only procedural
    // primitive geometry.
    implementation("org.jmonkeyengine:jme3-plugins:$jmeVersion")

    // Physics removed: player/boss movement is pure transform math (top-down,
    // no gravity). BulletAppState / Minie native-lib loading was the most
    // likely cause of the blank-screen crash on device.
}

kotlin {
    jvmToolchain(17)
}
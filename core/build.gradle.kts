plugins {
    kotlin("jvm")
}

repositories {
    google()
    mavenCentral()
}

val jmeVersion = "3.6.1-stable"

dependencies {
    // Platform-agnostic engine pieces only. No renderer and no Bullet
    // natives here -- the app module supplies the Android renderer and
    // Android-native physics libs on its own runtime classpath.
    implementation("org.jmonkeyengine:jme3-core:$jmeVersion")
    implementation("org.jmonkeyengine:jme3-effects:$jmeVersion")

    // Compile-only: gives us the com.jme3.bullet.* API surface (Minie is a
    // drop-in replacement for the discontinued jme3-bullet) without pulling
    // in native binaries. The "+bare" variant ships zero natives.
    compileOnly("com.github.stephengold:Minie:9.0.1+bare")
}

kotlin {
    jvmToolchain(17)
}

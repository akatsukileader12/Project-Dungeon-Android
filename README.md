# Project Dungeon (Android)

Android build of **Project Dungeon** — a jMonkeyEngine + Kotlin dungeon RPG with an isometric/MOBA-style camera and click/tap-to-move navigation.

This repo is the Android-only counterpart of [Project-Dungeon](https://github.com/akatsukileader12/Project-Dungeon) (the desktop version). Same game logic (`core` module), running through jME's Android renderer instead of LWJGL.

## Getting the APK — no Android Studio required

Every push to `main` builds a debug APK in GitHub Actions and uploads it as a workflow artifact:

1. Go to the **Actions** tab → the latest **Build Android APK** run
2. Download the `project-dungeon-android-debug-<sha>` artifact (a zip containing the `.apk`)
3. Install on a device: enable "install unknown apps" for your file manager/browser, then open the APK. Or via `adb install app-debug.apk` if you have `adb` (part of `platform-tools`, no full Android Studio needed).

Workflow artifacts expire after 90 days. For a permanent download link, push a version tag:

```bash
git tag v1.0.0
git push origin v1.0.0
```

This also builds the APK and attaches it to a GitHub **Release**, which stays available indefinitely.

## Building locally without Android Studio

You only need a JDK (17+) and the Android SDK command-line tools:

```bash
# one-time SDK setup
sdkmanager --sdk_root=$ANDROID_HOME "platform-tools" "platforms;android-34" "build-tools;34.0.0"
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

## Module layout

```
core/   Engine-agnostic game logic (DungeonGame.kt) -- no renderer, no
        platform-specific natives.
app/    Android application module: AndroidManifest, MainActivity
        (extends jME's AndroidHarness, loads DungeonGame by reflection).
```

## Stack

| Layer | Tech |
|-------|------|
| Engine | [jMonkeyEngine 3.6](https://jmonkeyengine.org/) |
| Language | Kotlin 1.9 (JVM 17) |
| Renderer | jME `jme3-android` (OpenGL ES via `AndroidHarness`) |
| Physics | [Minie](https://github.com/stephengold/Minie), Android-native (`+droid`) variant |
| Min OS | Android 8.0 (API 26) |

## What's in the starter

- **Fixed isometric camera** that follows the player — no rotation, MLBB/Diablo style
- **Tap-to-move** player (red box stand-in): raycast from touch → ground, walk toward point
- **Box-room dungeon** with four walls and static Bullet (Minie) physics collision
- **Lighting**: directional sun + low ambient + warm torch point light

## Natural next steps

- [ ] Swap the box player for a loaded `.glb`/`.gltf` character model
- [ ] Replace box walls with Kenney dungeon tile meshes
- [ ] On-screen virtual joystick/buttons HUD (currently tap-to-move only)
- [ ] Navmesh/A* pathing around obstacles
- [ ] Enemy state machine, inventory system, room transitions

## Free asset sources

| Source | Type | License |
|--------|------|---------|
| [Kenney.nl](https://kenney.nl) | Dungeon/RPG tile packs, characters, props | CC0 |
| [Quaternius](https://quaternius.com) | Low-poly characters & monsters | CC0 |
| [Poly Haven](https://polyhaven.com) | PBR stone/wood/floor textures | CC0 |
| [ambientCG](https://ambientcg.com) | PBR material library | CC0 |

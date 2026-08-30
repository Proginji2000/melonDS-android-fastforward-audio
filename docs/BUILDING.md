# Building

## Repository layout

This project consists of two repositories:

- Android/frontend: `Proginji2000/melonDS-android-fastforward-audio`
- modified core: `Proginji2000/melonDS-core-fastforward-audio`

The core is referenced as the `melonDS-android-lib` submodule.

## Clone

```bash
git clone --recurse-submodules https://github.com/Proginji2000/melonDS-android-fastforward-audio.git
cd melonDS-android-fastforward-audio
git checkout thor-fastforward-audio
git submodule update --init --recursive
```

To reproduce the Phase 3 checkpoint:

```bash
git checkout thor-pokemon-white-bgm-x1-phase3
git submodule update --init --recursive
```

## Required tools

The upstream Android project requires:

- Android SDK
- Android NDK
- CMake
- a compatible JDK

The validated Windows development environment used JDK 21.

Example:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
```

Android SDK used during validation:

```text
C:\Users\Quentin\AppData\Local\Android\Sdk
```

The SDK path is machine-specific and should not be committed.

## Debug APK

```powershell
.\gradlew.bat :app:assembleGitHubProdDebug
```

This is the normal development APK task used throughout the project.

## Unit tests

```powershell
.\gradlew.bat :app:testGitHubProdDebugUnitTest --rerun-tasks
```

At the Phase 3G checkpoint, the existing test suite completed successfully with 13 tests and no failures.

## Release-like native build

Performance validation uses the native `RelWithDebInfo` configuration:

```powershell
.\gradlew.bat :app:buildCMakeRelWithDebInfo
```

For Android `RelWithDebInfo`, `app/CMakeLists.txt` changes the normal `-O2` optimization level to `-O3` and enables ThinLTO for:

- melonDS core
- SoundTouch
- Android native frontend

The final frontend link also uses ThinLTO.

Validated Android ABIs:

```text
arm64-v8a
armeabi-v7a
x86_64
```

## SoundTouch

SoundTouch 2.4.1 is vendored under:

```text
app/src/main/cpp/third_party/soundtouch
```

The Android build creates a static `soundtouch` library with floating-point samples. NEON is enabled for ARM builds where applicable.

See:

```text
app/src/main/cpp/third_party/README.md
```

for exact version, upstream commit, licensing and binary redistribution notes.

## Release signing

The original Android project expects release signing information in `local.properties`:

```text
MELONDS_KEYSTORE=<path_to_your_keystore>
MELONDS_KEYSTORE_PASSWORD=<keystore_password>
MELONDS_KEY_ALIAS=<name_of_your_key_alias>
MELONDS_KEY_PASSWORD=<key_alias_password>
```

Never commit these values or the keystore.

## Local benchmark scripts

The current development machine has local PowerShell benchmark/profile helpers under `tools/`. They are intentionally not part of the committed product tree at the Phase 3 checkpoint.

Do not add them with a broad `git add .` unless they have first been reviewed for portability and private/device-specific data.

## Recommended pre-commit checks

```powershell
git diff --check
.\gradlew.bat :app:assembleGitHubProdDebug
.\gradlew.bat :app:testGitHubProdDebugUnitTest --rerun-tasks
.\gradlew.bat :app:buildCMakeRelWithDebInfo
```

For core changes, also run `git diff --check` inside `melonDS-android-lib` and verify the parent gitlink points to the intended core commit.

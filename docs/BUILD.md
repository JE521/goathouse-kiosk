# Building

## Toolchain (pinned)

| Tool | Version |
|---|---|
| JDK | 17 |
| Gradle | 8.9 (wrapper, `gradle/wrapper/`) |
| Android Gradle Plugin | 8.5.2 |
| compileSdk / targetSdk | 34 |
| minSdk | 21 (Android 5.0) |
| NDK | 27.3.13750724 (r27d), for the goatprint matcher only |
| ABIs | armeabi-v7a, arm64-v8a |

Install the SDK pieces with `sdkmanager`:

```sh
sdkmanager "platforms;android-34" "build-tools;34.0.0" "ndk;27.3.13750724"
```

## Build

```sh
export JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
./gradlew lintRelease              # keep at 0 errors
```

Offline after the first build: `./gradlew --offline assembleRelease`.

The only library dependency is ZXing core (QR codes). There's no AndroidX, no Compose and no XML layouts: the UI is
built in code. That keeps the APK small (~700 KB including both native ABIs) and fast on 1 GB tablets.

## Native code

`app/src/main/cpp/` is built with **ndk-build** (no CMake needed):

- `goatprint_jni.c`: JNI bridge (`GoatprintCore.java`).
- `goatprint/`: vendored from the goatprint project: the BioMini image normaliser (`src/image.c`), template
  serialisation and matching (`src/match.c`) and NIST NBIS MINDTCT/BOZORTH3 (`third_party/nbis`, public domain).
  See `goatprint/VENDORED.txt`.

USB itself is pure Java (`GoatprintReader.java`), so libusb isn't needed.

## House profile

If `house/house.json` exists (gitignored) it's packaged as `assets/house.json` and supplies default entity ids.
`house/backdrop.jpg` becomes the background photo. Without them the build is generic: no backdrop, generic
defaults. See `house.example.json`.

## Signing and updates

Release builds are currently signed with the **debug key** (`signingConfigs.debug`), which is fine for sideloading.
Android only installs an update signed with the same key, so:

- Builds from one machine update each other.
- A build from a different machine (or CI) won't install over it. Uninstall first (this loses settings) or move to
  a shared release keystore. That's on the roadmap, together with GitHub Releases for Obtainium.

## CI

`.github/workflows/build.yml` builds the APK on every push and pull request and uploads it as an artifact.

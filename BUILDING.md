# Build Folio

## Requirements

- JDK **21** (used for release-package verification; Android bytecode targets Java 17).
- Android SDK platform **36**, build-tools **36.0.0**, and platform-tools.
- Internet access for the first Gradle/dependency download.
- Android **8.0 / API 26+** device or emulator to run the app.

The wrapper pins Gradle **9.6.0** with a distribution checksum; AGP is **9.4.1**. Use an Android Studio version supporting that AGP, or use the command line. No particular Android Studio release was verified for this package.

Download and extract the source archive, or clone the repository from its actual published GitHub URL. Open the directory containing `settings.gradle.kts` in Android Studio. There is only one module, `:app`.

## Local SDK configuration

Point `JAVA_HOME` to your JDK 21 and `ANDROID_HOME` to your Android SDK. Android Studio may create a root `local.properties` with your `sdk.dir` instead. All paths must describe **your own** machine. `local.properties` is ignored by Git and is absent from this archive.

If SDK components are missing, install them with Android Studio SDK Manager or:

```text
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

Bundled scanner/OCR models and their dictionaries are already included; no model download is required for this source package. See [model hashes](docs/MODELS.md).

## Debug build and checks

Windows PowerShell, from the project root:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\scripts\check.ps1
```

Linux/macOS:

```sh
chmod +x gradlew
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

These commands work without Google credentials. Missing or placeholder `folio.google.webClientId` values leave Google sign-in unavailable with a configuration message; no fabricated credential is used.

## Install and device tests

Enable USB debugging, or start an API 26+ emulator. APKs are split into `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` under `app/build/outputs/apk/debug/`. Check `adb shell getprop ro.product.cpu.abi`; choose the matching APK.

```text
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

For an x86_64 emulator substitute `app-x86_64-debug.apk`. Do not overwrite a personally used Folio installation for automated tests; use a dedicated emulator.

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest :app:connectedDebugAndroidTest
# Equivalent existing verification script with device tests:
.\scripts\check.ps1 -Device
```

See [TESTING.md](docs/TESTING.md) for coverage and current failures/limitations. Tests may use Android file-picker or print-preview services and create temporary sample data.

## Optional Google Drive configuration

Follow [GOOGLE_DRIVE.md](docs/GOOGLE_DRIVE_SETUP.md). Copy `local.properties.example` to ignored `local.properties`, preserve/add your SDK settings if needed, and set **your public Web OAuth Client ID** as `folio.google.webClientId`. Register the Android client for `dev.folio.scanner` and your signing certificate. Do not add a Web client secret.

For the owner's distributed APK, Google accounts must be approved OAuth test users; a self-built app uses the builder's own configuration.

## Release signing

```powershell
.\gradlew.bat :app:assembleRelease
```

The current release build is **unsigned**; this archive intentionally contains no keystore or signing credentials. To distribute an APK, configure signing privately with your own key using Android Studio's signed APK workflow or a local Gradle configuration. Do not publish the key/passwords. Preserve AGPL corresponding source and third-party notices. This source archive does not create, sign or publish a GitHub APK release.


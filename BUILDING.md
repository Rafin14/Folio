# Build Folio

## Requirements

- JDK **21**; Android bytecode targets Java 17.
- Android SDK platform **37**, build-tools **36.0.0**, and platform-tools.
- NDK **27.2.12479018** and CMake **3.22.1**.
- Internet access for the first dependency download.
- Git LFS for the bundled PDF layout model.
- Android **8.0 / API 26+** to run the app.

The Gradle wrapper selects Gradle **9.6.0**. The project uses Android Gradle Plugin **9.4.1** and a single `:app` module.

Open the directory containing `settings.gradle.kts` in Android Studio. Install the required SDK components through SDK Manager. Set `JAVA_HOME` to JDK 21 and `ANDROID_HOME` to the Android SDK, or let Android Studio configure the SDK path.

All scanner, OCR and layout models are bundled. See [Models & OCR](docs/MODELS.md).

After cloning, install [Git LFS](https://git-lfs.com/) and download the model:

```sh
git lfs install
git lfs pull
```

GitHub's automatic source ZIP may contain an LFS pointer instead of the layout model. Clone the repository with Git LFS before building.

## Build an APK

From the project root on Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

On Linux or macOS:

```sh
chmod +x gradlew
./gradlew :app:assembleDebug
```

APKs are written to `app/build/outputs/apk/debug/`. Choose the APK matching the device architecture: `arm64-v8a`, `armeabi-v7a`, `x86`, or `x86_64`.

To build a universal APK for ARM 32-bit and 64-bit phones on Windows:

```powershell
.\gradlew.bat :app:assembleDebug '-Pfolio.releaseArmOnly=true'
```

The universal APK is `app/build/outputs/apk/debug/app-universal-debug.apk`. Debug APKs use Android debug signing.

Open the APK on the Android device to install it. Android may ask permission to install apps from the browser or file manager.

Google Drive Backup requires a configured Google OAuth client. Building without one leaves sign-in unavailable; the offline tools remain usable.

## License

Folio uses [GNU AGPL v3](LICENSE). Corresponding iText source archives are included in [third-party-sources](third-party-sources), with dependency notices in [Licenses & credits](docs/THIRD_PARTY.md).

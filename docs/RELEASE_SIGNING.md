# Android signing

Android uses the package name and signing certificate to identify compatible app updates. Keep the same signing key for builds you intend to install over one another. Before uninstalling to switch certificates, export your Library and verify the backup outside Folio. Android deletes local app data on uninstall.

## Build locally

`assembleDebug` uses the machine's Android debug key for development. For a signed production build, copy [keystore.properties.example](../keystore.properties.example) to the ignored `keystore.properties`. Set the absolute path to your release keystore outside the repository and configure the alias and passwords locally. Then run `:app:assembleRelease` with the Gradle wrapper.

Release signing verifies that the keystore contains an accessible, valid private key entry and rejects Android debug keys. To build an unsigned release APK for inspection, pass `-Pfolio.unsignedRelease=true`. An unsigned APK must be signed before installation.

## Verify and back up

Use Android SDK build-tools to run `apksigner verify --print-certs PATH_TO_APK`. Check that the certificate matches the intended distribution channel. If you use Google Drive Backup, register the application ID and certificate SHA-1 in your own Google Cloud project. See [Google Drive setup](GOOGLE_DRIVE_SETUP.md).

Store an encrypted backup of the keystore and its recovery credentials in a secure location outside the repository. Keep signing keys, credentials, and configured machine properties out of Git.

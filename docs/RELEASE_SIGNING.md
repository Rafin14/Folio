# Release signing

Android uses the signing certificate to identify an app and permit compatible updates. An APK signed with a different key cannot update an installed copy with the same application ID.

Folio's application ID is `dev.folio.scanner`. The current release configuration requires the existing certificate with SHA-1 `93:3B:CF:4C:3B:98:75:80:5B:F9:A8:DF:05:74:1F:79:DD:85:50:75`. A newly generated key cannot reproduce that certificate. Debug builds use the local Android debug certificate and remain available independently.

## Configure a source build

Keep the existing release keystore outside the source directory. Copy the root [configuration template](../keystore.properties.example) to `keystore.properties` and configure it locally. The required properties are `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. Use an absolute keystore path and the credentials for that existing key.

`keystore.properties` and keystore files are ignored by Git and must never be included in public source packages. Do not publish passwords, private keys, or tokens.

From the project root on Windows:

```powershell
.\gradlew.bat :app:assembleRelease '-Pfolio.releaseArmOnly=true'
```

Gradle rejects missing or invalid credentials, a debug key, a keystore inside the repository, or a certificate that does not match the required identity. A successful debug build does not establish release-signing compatibility.

Verify an APK with the Android SDK's `apksigner verify --print-certs` command. The actual signing certificate and application ID must also match the Android OAuth client used for Google sign-in. Configuring the expected fingerprint as text does not change an APK's certificate.

Keep encrypted backups of the original keystore and its credentials in separate secure locations. Losing the signing key can prevent compatible updates outside stores that manage app signing. Never replace or rotate an established key casually.

# Optional Google Drive backup

## Public release access

**Google Drive Backup is currently restricted to approved Google OAuth test users.**

Users of a downloaded Folio APK must contact the project owner with the Google account email address they intend to use. The owner must add that account to the Google Cloud OAuth **Test Users** list before sign-in/backup can work. Use the public contact method provided by the project repository/profile, if available. No personal contact address or existing test-user accounts are published here.

Unrestricted Google accounts are **not** currently supported by the owner's public release configuration. This source archive has no owner Client ID. Builders configure their own Cloud project and OAuth users.

## Set up a source build

1. Create/use your own Google Cloud project and enable Google Drive API.
2. Configure its OAuth consent screen. While in Testing, add the intended account to **Test Users**. Configure the scopes used below.
3. Create an **Android** OAuth client for package `dev.folio.scanner` and the SHA-1 of the certificate signing your APK. `gradlew :app:signingReport` shows your local debug certificate; production signing needs its own registration.
4. Create a **Web application** OAuth client in that same Cloud project. Folio's Credential Manager uses its public Client ID as `serverClientId`.
5. Put only that public ID in ignored root `local.properties`:

   ```properties
   folio.google.webClientId=YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
   ```

6. Rebuild/install the app. Connect your account from Google Drive Backup settings and complete Google's consent flow on a device with Google Play services.

**Never add a Web OAuth client secret**, service account or private key. Missing/placeholder IDs keep the app buildable and show the configuration-required UI.

Folio requests `https://www.googleapis.com/auth/drive.file` for its backup resources. Optional **Authorize safe folder inspection** requests `https://www.googleapis.com/auth/drive.metadata.readonly` so cloud purge can review folder children without assuming their ownership. This read-only metadata scope is broader visibility; content deletion remains restricted by Folio's ownership checks and the write scope. Configure this scope if Google's consent setup requires it.

## Current backup behavior

Connecting does not immediately upload. Automatic backup is off until explicitly enabled. Backup transfers use WorkManager, durable Room state and account-associated mappings; interruptions/authentication failures can require retry or sign-in.

The current **restore-first safety policy** locks fresh-install uploads until an existing **nonempty** Folio backup is restored. A brand-new account with no existing backup cannot establish its first backup through that fresh-install flow. This package preserves that behavior rather than weakening the safety lock.

Backups contain original and processed page images, library/page metadata, recoverable trash and compatible OCR results. Exported PDFs, unfinished scanner drafts and annotations are not backed up. Restore checks cloud data before importing it; it does not mean every historical model/version is compatible.

## Deletion and account safety

- Moving documents/pages to Recycle Bin keeps their cloud-backed contents recoverable.
- Permanent local deletion queues corresponding cloud removal. The cloud may remain until network, authentication and ownership checks succeed. **Retry Drive removals** retries durable removal work.
- **Delete All Cloud Backup** uses two confirmations, including exact uppercase `DELETE`. It removes positively identified Folio backup resources and leaves local documents intact. Unknown ownership is left for review; explicit confirmation does not override that boundary.
- Purge verifies cloud absence, distinguishes pending/failed/ambiguous work, and resumes after interruption. Successful purge suppresses automatic recreation; an upload-eligible user can explicitly choose **Back Up Now** to create another backup.
- Pending cloud operations are tied to their Google account; signing into another account must not execute the first account's removals.
- Disconnecting does not delete cloud data; purging does not disconnect the account.

## Live checks

Use a dedicated test account and nonsensitive sample documents. Authenticate, verify Folio's backup resources, and leave an unrelated Drive file as a safety control. Test restore, permanent deletion, offline retry and explicit purge; then independently query Drive to verify resource absence and retention of the unrelated file. Check that local documents remain, automatic upload stays suppressed after purge, and an eligible explicit backup creates new resources.

The public source tests use fake Drive transports. Account-specific destructive live-test harnesses are excluded. Release-package verification does not authenticate, delete or recreate the owner's real Drive backup.


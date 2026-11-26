# Google Drive Backup

## Availability

Google Drive Backup is optional. Configure a Google Cloud OAuth project for your build. If the OAuth app is in Testing, only accounts listed as test users in that project can connect. Scanning, editing, OCR and PDF tools work without a Google account.

## Connect and back up

Open **Settings → Backup → Google Drive Backup** and connect an eligible Google account. Complete Google's consent flow when prompted.

For source builds, configure the public Web OAuth client ID as `folio.google.webClientId` in the root `local.properties`. In the same Google Cloud project, enable Drive API and register an Android OAuth client for `dev.folio.scanner` and the SHA-1 of the certificate that signs the installed APK. Use the SHA-1 from `apksigner verify --print-certs` on that APK. Debug and release builds may require separate Android clients. If the OAuth app is in Testing, add the account under Audience before it requests Drive access.

Connecting an account does not upload documents. Automatic backup requires explicit opt-in.

On a fresh installation, choose **Find backup to restore** to check the connected account. If a backup exists, restore it before uploading. If the check confirms there is no completed backup, uploads are unlocked without a restore. A verified **Delete All Cloud Backup** also removes the restore requirement, including after restarting Folio. Network, authorization, or inspection failures do not count as an empty backup. Automatic backup stays paused after a purge until you explicitly choose **Back Up Now** and enable it again.

Backups include saved library PDFs, original and edited page images, document metadata, recoverable Recycle Bin contents and supported OCR results. External exports, unfinished scans and temporary PDF Workspace sessions are excluded.

## Permissions

Folio requests permission to manage its Google Drive backup files. Optional **Authorize safe folder inspection** grants read-only access to Drive metadata so Folio can check folder contents before deletion. Files whose ownership cannot be established are left untouched.

## Restore

Restore imports an existing Folio backup into the local library. Keep a separate copy of important documents and review restored content before relying on it.

## Delete and disconnect

- Moving documents or pages to the Recycle Bin keeps their backup data recoverable.
- Permanent deletion queues removal of the corresponding backup data. Removal may remain pending while offline, signed out or awaiting an ownership check. **Retry Drive removals** retries pending work.
- **Delete All Cloud Backup** permanently removes identified Folio backup data. It requires two confirmations, including typing uppercase `DELETE`. Local documents remain on the device.
- After a successful cloud purge, automatic backup does not recreate the deleted backup. When uploads are available, **Back Up Now** can create a new backup deliberately.
- Pending operations belong to the account that requested them. Connecting a different account does not transfer those operations.
- Disconnecting leaves the cloud backup intact. Deleting a cloud backup does not disconnect the account.

Cloud deletion cannot be undone. Folio shows pending, failed or review-required status when deletion has not completed.

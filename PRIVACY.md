# Privacy

Folio stores saved PDFs, original/processed page images, thumbnails, library metadata and OCR text in app storage on the Android device. OCR and document detection use bundled models locally; no OCR service uploads document content. Folio contains no advertising or analytics SDK. Android OS app backup is disabled.

Camera access is requested for scanning. Device file selection uses Android system pickers; sharing uses the Android Sharesheet, printing uses Android's print framework, and export uses a location chosen by the user. The destination provider, share recipient or print service can receive the selected content. Folio may keep temporary export/print files in app cache.

Google Drive backup is optional and requires authentication/consent. Enabling it sends the backed-up PDFs, document images, metadata and supported OCR results to the selected Google account. Folio stores account identity and durable cloud mappings locally to reconcile and retry operations. Optional read-only Drive metadata access is used to inspect folder ownership safely. See [the Drive guide](docs/GOOGLE_DRIVE_SETUP.md) for access limits, restore and deletion behavior.

Moving a document/page to Recycle Bin retains recoverable data; after permanent deletion or 60 days it is removed locally. Corresponding cloud deletion can remain pending while offline, unauthenticated or awaiting ownership review. Disconnecting Google does not delete an existing backup. Explicit cloud purge leaves local documents intact.

Protect exports and backups appropriately; they can contain sensitive documents. Password protection applies only where explicitly offered for a generated PDF. Do not assume the whole local library is separately encrypted or that deleting app data also deletes exported/cloud copies.


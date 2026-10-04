# Verification and testing

Release-package preparation on **2026-10-04** tested the cleaned staging project, with JDK 21 and an Android API 36 x86_64 emulator. The app version remains **0.1.0**. These are executed checks, not a guarantee of every phone/provider combination.

## Executed staging checks

| Check | Result |
| --- | --- |
| `:app:assembleDebug` | Passed; four ABI-specific debug APKs generated. |
| `:app:assembleDebugAndroidTest` | Passed. |
| `:app:testDebugUnitTest` | **58 tests passed**, zero failures/errors. |
| `:app:lintDebug` | Passed with **0 errors, 31 warnings, 1 hint**. Warnings are retained, not suppressed for packaging. |
| `:app:assembleRelease` | Passed; unsigned release APKs generated. Existing R8 service-reference warnings remain. No signed APK is supplied. |
| `:app:connectedDebugAndroidTest` | **120 existing tests across 44 classes passed**, zero failures/ignored tests. |
| `ReleaseScreenshotsTest` separately | **1 test passed** after the full suite; real production screens captured with safe generated sample content. |
| `scripts/check.ps1` | Passed: existing debug build, unit-test and lint verification. |

The 120-test run covers native scanner/crop processing, retained originals and draft recovery, editing/page-size behavior, library actions/selection, OCR correctness/provider fallback/search, Room migrations, page/document trash, PDF generation/manipulation/replacement/export/file pickers, printing, fake-transport Drive backup/deletion/purge safety, restoration and durable operation recovery. It is not live Google Drive or physical print-output validation.

The screenshot test was added only to the release staging test source; production application logic was preserved. Its OCR search screenshot uses seeded sample OCR text matching the generated sample page to show the viewer clearly. Real model recognition is exercised separately by `OcrIntegrationTest` and `OcrProviderTest`. The replacement screenshot is an existing real app capture with a nonsensitive solid-color fixture.

## Repeat locally

```powershell
.\scripts\check.ps1
# A dedicated emulator/device must be running for the following:
.\scripts\check.ps1 -Device
```

For just the gallery:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.folio.scanner.ReleaseScreenshotsTest'
```

Gallery captures are written to `Download/Folio-release-gallery` on the test device so they survive test-APK removal. Other tests can create sample files in Downloads or app cache. Use a dedicated emulator/test installation, not a personal library. Start from a clean install for reproducible public screenshots. Tests can take several minutes and invoke Android camera, file-picker, Sharesheet or print-preview services.

## Separate manual acceptance

- Real rear-camera paper capture, autofocus/resolution, motion/lighting and scanner responsiveness on a physical phone.
- Actual OCR accelerator node assignment and performance on each device; provider availability alone is not a GPU result.
- Real print-service/paper output, different file providers and retained folder access across device reboot.
- Google sign-in with a correctly configured client/approved test account, live backup/restore, removal retry and verified Drive cloud purge; follow [the safe procedure](GOOGLE_DRIVE_SETUP.md).
- Large and unusual PDFs, unsupported encryption/features, varied OCR languages/handwriting and tablet/large-font ergonomics beyond automated cases.

No physical phone or authenticated live Drive account was used for this release-package run. Account-specific live destructive tests and their private operational reports are excluded from the public archive. No OCR GPU, signed-release, unrestricted Drive-access or universal device-compatibility claim is made.


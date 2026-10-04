param([switch]$Device)
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    & .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Build, unit tests, or lint failed.' }
    if ($Device) {
        & .\gradlew.bat :app:connectedDebugAndroidTest --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Device tests failed.' }
    }
} finally { Pop-Location }

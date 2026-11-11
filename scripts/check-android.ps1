$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    & .\gradlew.bat :vehicle:assembleDebug :companion:assembleDebug :vehicle:lintDebug :companion:lintDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android build or lint failed' }
    foreach ($module in @('vehicle', 'companion')) {
        $apkPath = Join-Path $module ('build\outputs\apk\debug\' + $module + '-debug.apk')
        if (!(Test-Path -LiteralPath $apkPath)) { throw "Missing $module APK" }
    }
    Write-Output 'ANDROID_CHECKS_PASSED'
} finally { Pop-Location }

# Builds the signed Play Store bundle (.aab).
# The password is asked for here and only lives in this process; nothing is written to disk.
#
# usage (from the repo root):  powershell -ExecutionPolicy Bypass -File tools/release/build-bundle.ps1
#        optional:             -Version 1.0.1   (versionCode = major*10000 + minor*100 + patch, as in CI)
#                              -Keystore D:/Keys/deckle-upload.jks -Alias deckle
param(
    [string]$Version = "1.0.0",
    [string]$Keystore = "D:/Keys/deckle-upload.jks",
    [string]$Alias = "deckle"
)
$ErrorActionPreference = "Stop"

if ($Version -notmatch '^(\d+)\.(\d+)\.(\d+)$') { throw "Version must be MAJOR.MINOR.PATCH, e.g. 1.0.1" }
$code = [int]$Matches[1] * 10000 + [int]$Matches[2] * 100 + [int]$Matches[3]
Write-Host "Building $Version (versionCode $code)"

if (-not (Test-Path $Keystore)) { throw "Keystore not found: $Keystore" }

$secure = Read-Host "Keystore password" -AsSecureString
$plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))

try {
    $env:DECKLE_KEYSTORE_PATH = (Resolve-Path $Keystore).Path
    $env:DECKLE_KEYSTORE_PASSWORD = $plain
    $env:DECKLE_KEY_ALIAS = $Alias
    # keytool's default PKCS12 keystores use the same password for the key.
    $env:DECKLE_KEY_PASSWORD = $plain

    & ./gradlew.bat bundleRelease --no-daemon "-Pdeckle.versionCode=$code" "-Pdeckle.versionName=$Version"
    if ($LASTEXITCODE -ne 0) { throw "Build failed" }

    $aab = "app/build/outputs/bundle/release/app-release.aab"
    Write-Host ""
    Write-Host "Signed bundle $Version ($code): $((Resolve-Path $aab).Path)" -ForegroundColor Green
    Write-Host "Upload it in Play Console > Test and release > Closed testing > Create new release."
}
finally {
    Remove-Item Env:DECKLE_KEYSTORE_PASSWORD, Env:DECKLE_KEY_PASSWORD, Env:DECKLE_KEYSTORE_PATH, Env:DECKLE_KEY_ALIAS -ErrorAction SilentlyContinue
    $plain = $null
}

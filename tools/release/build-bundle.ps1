# Builds the signed Play Store bundle (.aab).
# The password is asked for here and only lives in this process; nothing is written to disk.
#
# usage (from the repo root):  powershell -ExecutionPolicy Bypass -File tools/release/build-bundle.ps1
#        optional:             -Keystore D:/Keys/deckle-upload.jks -Alias deckle
param(
    [string]$Keystore = "D:/Keys/deckle-upload.jks",
    [string]$Alias = "deckle"
)
$ErrorActionPreference = "Stop"

if (-not (Test-Path $Keystore)) { throw "Keystore not found: $Keystore" }

$secure = Read-Host "Keystore password" -AsSecureString
$plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))

try {
    $env:DECKLE_KEYSTORE_PATH = (Resolve-Path $Keystore).Path
    $env:DECKLE_KEYSTORE_PASSWORD = $plain
    $env:DECKLE_KEY_ALIAS = $Alias
    # keytool's default PKCS12 keystores use the same password for the key.
    $env:DECKLE_KEY_PASSWORD = $plain

    & ./gradlew.bat bundleRelease --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Build failed" }

    $aab = "app/build/outputs/bundle/release/app-release.aab"
    Write-Host ""
    Write-Host "Signed bundle: $((Resolve-Path $aab).Path)" -ForegroundColor Green
    Write-Host "Upload it in Play Console > Test and release > Closed testing > Create new release."
}
finally {
    Remove-Item Env:DECKLE_KEYSTORE_PASSWORD, Env:DECKLE_KEY_PASSWORD, Env:DECKLE_KEYSTORE_PATH, Env:DECKLE_KEY_ALIAS -ErrorAction SilentlyContinue
    $plain = $null
}

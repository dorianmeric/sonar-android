# Generates the release keystore and the matching keystore.properties used by app/build.gradle.kts.
#
#   powershell -ExecutionPolicy Bypass -File scripts/create-keystore.ps1
#
# The generated keystore is a local demo signing identity. Keep the .jks file and its passwords if
# you intend to publish updates under the same application id.

param(
    [string]$StorePath = "keystore/echosonar-release.jks",
    [string]$Alias = "echosonar",
    [string]$Password = "echosonar-demo",
    [string]$DistinguishedName = "CN=EchoSonar, OU=Sensor Lab, O=EchoSonar, L=Unknown, ST=Unknown, C=GB"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$storeFull = Join-Path $root $StorePath
$keystoreDir = Split-Path -Parent $storeFull

if (-not (Test-Path $keystoreDir)) {
    New-Item -ItemType Directory -Path $keystoreDir | Out-Null
}

$keytool = $null
foreach ($candidate in @(
        "C:\P\Android Studio\jbr\bin\keytool.exe",
        (Join-Path $env:JAVA_HOME "bin\keytool.exe"))) {
    if ($candidate -and (Test-Path $candidate)) { $keytool = $candidate; break }
}
if (-not $keytool) {
    $command = Get-Command keytool -ErrorAction SilentlyContinue
    if ($command) { $keytool = $command.Source }
}
if (-not $keytool) { throw "keytool not found. Set JAVA_HOME to a JDK installation." }

if (Test-Path $storeFull) {
    Write-Host "Keystore already exists: $storeFull"
} else {
    Write-Host "Generating keystore: $storeFull"
    & $keytool -genkeypair `
        -keystore $storeFull `
        -alias $Alias `
        -keyalg RSA `
        -keysize 4096 `
        -validity 10000 `
        -storetype PKCS12 `
        -storepass $Password `
        -keypass $Password `
        -dname $DistinguishedName
    if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit code $LASTEXITCODE" }
}

$propertiesPath = Join-Path $root "keystore.properties"
@(
    "storeFile=$StorePath",
    "storePassword=$Password",
    "keyAlias=$Alias",
    "keyPassword=$Password"
) | Set-Content -Path $propertiesPath -Encoding ASCII

Write-Host "Wrote $propertiesPath"
Write-Host "Release builds will now be signed automatically (assembleRelease)."

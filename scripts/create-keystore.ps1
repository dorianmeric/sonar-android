# Generates the release signing keystore and the matching keystore.properties.
#
#   powershell -ExecutionPolicy Bypass -File scripts/create-keystore.ps1
#
# Security rules enforced here:
#   * the keystore lives OUTSIDE the git working tree by default (~/.keys/echosonar),
#   * the password is generated from a cryptographic RNG unless supplied explicitly,
#   * nothing secret is ever written inside the repository,
#   * the generated files are locked down to the current user with icacls.
#
# Save the printed password in a password manager. Losing it means you can no longer
# publish updates under the same application id.

param(
    [string]$StorePath = "",
    [string]$Alias = "echosonar-release",
    [string]$Password = "",
    [string]$DistinguishedName = "CN=EchoSonar, OU=Sensor Lab, O=EchoSonar, L=Unknown, ST=Unknown, C=GB",
    [switch]$AllowInsideRepo
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$repoRoot = (Resolve-Path $root).Path

if (-not $StorePath) {
    $StorePath = Join-Path $env:USERPROFILE ".keys\echosonar\release.jks"
}
$storeFull = [System.IO.Path]::GetFullPath($StorePath)

# Guard: signing material must never live inside the repository.
$insideRepo = $storeFull.StartsWith($repoRoot, [System.StringComparison]::OrdinalIgnoreCase)
if ($insideRepo -and -not $AllowInsideRepo) {
    throw "Refusing to place a keystore inside the git working tree ($repoRoot). Pass -StorePath outside the repo."
}

$keystoreDir = Split-Path -Parent $storeFull
if (-not (Test-Path $keystoreDir)) {
    New-Item -ItemType Directory -Path $keystoreDir -Force | Out-Null
}

# Find keytool (Android Studio's JBR is enough).
$keytool = $null
foreach ($candidate in @(
        (Join-Path $env:JAVA_HOME "bin\keytool.exe"),
        "C:\P\Android Studio\jbr\bin\keytool.exe",
        "C:\Program Files\Java\jdk-21\bin\keytool.exe",
        "C:\Program Files\Java\jdk-17\bin\keytool.exe")) {
    if ($candidate -and (Test-Path $candidate)) { $keytool = $candidate; break }
}
if (-not $keytool) {
    $command = Get-Command keytool -ErrorAction SilentlyContinue
    if ($command) { $keytool = $command.Source }
}
if (-not $keytool) { throw "keytool not found. Set JAVA_HOME to a JDK installation." }

if (-not $Password) {
    $bytes = [byte[]]::new(32)
    # RandomNumberGenerator::Fill only exists on .NET Core; Create()/GetBytes works on both
    # Windows PowerShell 5.1 and PowerShell 7.
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $Password = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $generated = $true
} else {
    $generated = $false
}

if (Test-Path $storeFull) {
    Write-Host "Keystore already exists: $storeFull"
    Write-Host "Refusing to overwrite. Delete it first if you really intend to rotate the signing key."
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

# Properties files treat backslash as an escape character, so store forward slashes.
$storeForProperties = $storeFull.Replace('\', '/')
$propertiesPath = Join-Path $repoRoot "keystore.properties"
@(
    "# LOCAL ONLY - must never be committed (see .gitignore).",
    "storeFile=$storeForProperties",
    "storePassword=$Password",
    "keyAlias=$Alias",
    "keyPassword=$Password"
) | Set-Content -Path $propertiesPath -Encoding ASCII

# Restrict access to the current user where possible.
foreach ($secret in @($storeFull, $propertiesPath)) {
    try {
        & icacls $secret /inheritance:r /grant:r "$($env:USERNAME):(F)" | Out-Null
    } catch {
        Write-Warning "Could not tighten ACL on $secret (continuing)."
    }
}

Write-Host ""
Write-Host "Wrote $propertiesPath (git-ignored)"
if ($generated) {
    Write-Host ""
    Write-Host "Generated signing password: $Password" -ForegroundColor Yellow
    Write-Host "Store it in a password manager now. It is shown only once."
}
Write-Host ""
Write-Host "Build the signed APK with:  powershell -ExecutionPolicy Bypass -File scripts\build-release.ps1"
Write-Host "If you ever suspect this key leaked: rotate it (delete the .jks, rerun this script," +
    " and remember that old APKs signed with it can no longer be updated in place)."
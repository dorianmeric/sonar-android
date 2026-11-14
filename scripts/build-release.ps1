# Builds, tests, signs and verifies the release APK, then publishes it to release/.
#
#   powershell -ExecutionPolicy Bypass -File scripts/build-release.ps1
#
# Requires the Android SDK path in local.properties (or ANDROID_HOME) and a JDK 17+.

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not $env:JAVA_HOME) {
    $candidates = @(
        "C:\P\Android Studio\jbr",
        "C:\Program Files\Java\jdk-21",
        "C:\Program Files\Java\jdk-17"
    ) | Where-Object { Test-Path (Join-Path $_ "bin\java.exe") }
    if ($candidates.Count -gt 0) {
        $env:JAVA_HOME = $candidates[0]
    } else {
        throw "JAVA_HOME is not set and no JDK was found."
    }
}
Write-Host "JAVA_HOME=$env:JAVA_HOME"

if (-not (Test-Path (Join-Path $root "keystore.properties"))) {
    Write-Host "No keystore.properties, generating a signing identity..."
    & powershell -ExecutionPolicy Bypass -File (Join-Path $root "scripts\create-keystore.ps1")
}

$gradle = Join-Path $root "gradlew.bat"
Write-Host "`n=== unit tests ==="
& $gradle testDebugUnitTest --console=plain
if ($LASTEXITCODE -ne 0) { throw "Unit tests failed." }

Write-Host "`n=== lint ==="
& $gradle lintDebug --console=plain
if ($LASTEXITCODE -ne 0) { throw "Lint failed." }

Write-Host "`n=== release build ==="
& $gradle assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw "Release build failed." }

$apk = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apk)) { throw "APK not found at $apk" }

$versionLine = Select-String -Path (Join-Path $root "app\build.gradle.kts") -Pattern 'versionName\s*=\s*"([^"]+)"'
$version = if ($versionLine) { $versionLine.Matches[0].Groups[1].Value } else { "unknown" }
$releaseDir = Join-Path $root "release"
New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
$target = Join-Path $releaseDir ("EchoSonar-$version.apk")
Copy-Item -Force $apk $target

Write-Host "`n=== signature ==="
$buildTools = $null
$sdkCandidates = @()
if (Test-Path (Join-Path $root "local.properties")) {
    $sdkLine = Select-String -Path (Join-Path $root "local.properties") -Pattern 'sdk\.dir=(.+)' | Select-Object -First 1
    if ($sdkLine) {
        $sdkPath = $sdkLine.Matches[0].Groups[1].Value -replace '\\\\', '\' -replace '\\:', ':'
        $sdkCandidates += $sdkPath
    }
}
if ($env:ANDROID_HOME) { $sdkCandidates += $env:ANDROID_HOME }
if ($env:ANDROID_SDK_ROOT) { $sdkCandidates += $env:ANDROID_SDK_ROOT }
foreach ($sdk in $sdkCandidates) {
    $bt = Get-ChildItem (Join-Path $sdk "build-tools") -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($bt -and (Test-Path (Join-Path $bt.FullName "apksigner.bat"))) {
        $buildTools = $bt.FullName
        break
    }
}
if ($buildTools) {
    & (Join-Path $buildTools "apksigner.bat") verify --print-certs $target
} else {
    Write-Warning "apksigner not found; skipping signature verification."
}

$hash = (Get-FileHash $target -Algorithm SHA256).Hash
"$hash  EchoSonar-$version.apk" | Set-Content (Join-Path $releaseDir "EchoSonar-$version.apk.sha256") -Encoding ASCII

$info = Get-Item $target
Write-Host "`n=== artifact ==="
Write-Host ("{0}  ({1:N2} MB)" -f $info.FullName, ($info.Length / 1MB))
Write-Host "SHA-256: $hash"
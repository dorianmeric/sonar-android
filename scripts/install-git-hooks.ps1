# Enables the version-controlled git hooks in .githooks/.
#
#   powershell -ExecutionPolicy Bypass -File scripts/install-git-hooks.ps1

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$hookDir = Join-Path $root ".githooks"
if (-not (Test-Path (Join-Path $hookDir "pre-commit"))) {
    throw "Missing .githooks/pre-commit"
}

git config core.hooksPath .githooks
Write-Host "core.hooksPath = $(git config core.hooksPath)"

# Git for Windows runs hooks through sh. Mark the hook executable in the index so Unix checkouts
# behave the same way (`--add` keeps this idempotent before the file is tracked).
try {
    & git update-index --add --chmod=+x .githooks/pre-commit 2>$null
} catch {
    Write-Warning "Could not set the executable bit in the index (it will be set on the next commit)."
}

Write-Host "Pre-commit secret guard installed."
Write-Host "Test it with:"
Write-Host '  "x" > test.jks; git add -f test.jks; git commit -m test   # must be refused'
Write-Host '  "storePassword=<example-value>" > n.txt; git add n.txt; git commit -m t  # must be refused'
<#
  Build Maintenance Manager from source on Windows, then install it (icons and all).
  Most people should use the prebuilt "MaintenanceManager-Windows.zip" and its
  "Install Maintenance Manager.bat" instead; this is for building from the source code.

  Needs Java 21 + Maven + Node.js.  Usage (from the maintenance-app folder):
    powershell -ExecutionPolicy Bypass -File windows\setup.ps1 [-StartWithWindows]
#>
param([switch]$StartWithWindows)
$ErrorActionPreference = 'Stop'
$Repo = Split-Path $PSScriptRoot -Parent

foreach ($cmd in @('mvn.cmd', 'npm.cmd')) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) { throw "$cmd not found. Install it, open a NEW PowerShell window and try again." }
}

Write-Host '==> Building the server' -ForegroundColor Cyan
Push-Location (Join-Path $Repo 'backend')
try { & mvn.cmd -q -B -DskipTests package; if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' } } finally { Pop-Location }

Write-Host '==> Building the web pages' -ForegroundColor Cyan
Push-Location (Join-Path $Repo 'frontend')
try {
    & npm.cmd ci --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { throw 'npm install failed.' }
    & npm.cmd run build; if ($LASTEXITCODE -ne 0) { throw 'Web build failed.' }
} finally { Pop-Location }

$stage = Join-Path $env:TEMP 'MaintenanceManager-build'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$stage\app" | Out-Null
Copy-Item (Join-Path $Repo 'backend\target\cmms-api.jar') "$stage\app\cmms-api.jar"
Copy-Item (Join-Path $Repo 'frontend\dist') "$stage\app\web" -Recurse
New-Item -ItemType Directory -Force -Path "$stage\worker" | Out-Null
Copy-Item (Join-Path $Repo 'worker\cmms_worker') "$stage\worker\cmms_worker" -Recurse
Copy-Item (Join-Path $Repo 'worker\requirements.txt') "$stage\worker\requirements.txt"

& (Join-Path $PSScriptRoot 'install.ps1') -Source $stage -StartWithWindows:$StartWithWindows

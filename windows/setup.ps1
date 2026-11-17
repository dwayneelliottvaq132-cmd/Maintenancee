<#
  Maintenance Manager - Windows desktop setup.

  Builds the app, installs it to %LOCALAPPDATA%\MaintenanceManager and creates
  Desktop + Start menu icons. Run it again after updating the code to upgrade
  (your settings and data are kept).

  Usage (from the maintenance-app folder):
    powershell -ExecutionPolicy Bypass -File windows\setup.ps1
    powershell -ExecutionPolicy Bypass -File windows\setup.ps1 -StartWithWindows   # also start the server at login
    powershell -ExecutionPolicy Bypass -File windows\setup.ps1 -Uninstall          # remove icons and app files

  Needs (already installed if you followed the Windows steps): Java 21, Maven, Node.js,
  PostgreSQL with database "cmms" owned by user "cmms".
#>
param(
    [switch]$StartWithWindows,
    [switch]$SkipBuild,
    [switch]$Uninstall
)
$ErrorActionPreference = 'Stop'
$Repo = Split-Path $PSScriptRoot -Parent
$AppHome = Join-Path $env:LOCALAPPDATA 'MaintenanceManager'
$Desktop = [Environment]::GetFolderPath('Desktop')
$Programs = [Environment]::GetFolderPath('Programs')
$Startup = [Environment]::GetFolderPath('Startup')
$ShortcutFiles = @(
    (Join-Path $Desktop 'Maintenance Manager.lnk'),
    (Join-Path $Programs 'Maintenance Manager.lnk'),
    (Join-Path $Programs 'Stop Maintenance Manager.lnk'),
    (Join-Path $Startup 'Maintenance Manager Server.lnk')
)

function Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }

function Stop-App {
    Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" |
        Where-Object { $_.CommandLine -like '*cmms-api.jar*' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

if ($Uninstall) {
    Step 'Stopping Maintenance Manager'
    Stop-App
    Start-Sleep -Seconds 2
    $ShortcutFiles | Where-Object { Test-Path $_ } | ForEach-Object { Remove-Item $_ -Force }
    if (Test-Path "$AppHome\app") { Remove-Item "$AppHome\app" -Recurse -Force }
    Write-Host "Icons and program files removed. Your settings and uploaded files are still in $AppHome"
    Write-Host "(delete that folder too if you want them gone; the PostgreSQL database is not touched)."
    exit 0
}

# ---------- prerequisites ----------
Step 'Checking prerequisites'
function Require($cmd, $hint) {
    $c = Get-Command $cmd -ErrorAction SilentlyContinue
    if (-not $c) { throw "$cmd was not found. $hint Then open a NEW PowerShell window and run setup again." }
    return $c.Source
}
$java = Require 'java' 'Install Java 21 (winget install EclipseAdoptium.Temurin.21.JDK).'
$javaw = Join-Path (Split-Path $java) 'javaw.exe'
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javaw.exe'))) { $javaw = Join-Path $env:JAVA_HOME 'bin\javaw.exe' }
if (-not (Test-Path $javaw)) { throw "javaw.exe not found next to $java" }
if (-not $SkipBuild) {
    Require 'mvn.cmd' 'Install Maven (see the Maven step in the instructions).' | Out-Null
    Require 'npm.cmd' 'Install Node.js (winget install OpenJS.NodeJS.LTS).' | Out-Null
}

# ---------- build ----------
if (-not $SkipBuild) {
    Step 'Building the server (first time takes a few minutes)'
    Push-Location (Join-Path $Repo 'backend')
    try {
        & mvn.cmd -q -B -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Maven build failed - see the messages above.' }
    } finally { Pop-Location }

    Step 'Building the web pages'
    Push-Location (Join-Path $Repo 'frontend')
    try {
        & npm.cmd ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'npm install failed - see the messages above.' }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Web build failed - see the messages above.' }
    } finally { Pop-Location }
}
$jar = Join-Path $Repo 'backend\target\cmms-api.jar'
$web = Join-Path $Repo 'frontend\dist'
if (-not (Test-Path $jar) -or -not (Test-Path "$web\index.html")) { throw 'Build output not found. Run setup without -SkipBuild.' }

# ---------- install files ----------
Step "Installing to $AppHome"
Stop-App
Start-Sleep -Seconds 1
foreach ($d in @("$AppHome", "$AppHome\app", "$AppHome\logs", "$AppHome\data\uploads", "$AppHome\data\reports")) {
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}
Copy-Item $jar "$AppHome\app\cmms-api.jar" -Force
if (Test-Path "$AppHome\app\web") { Remove-Item "$AppHome\app\web" -Recurse -Force }
Copy-Item $web "$AppHome\app\web" -Recurse
foreach ($f in @('launch.ps1', 'stop.ps1', 'maintenance.ico')) { Copy-Item (Join-Path $PSScriptRoot $f) "$AppHome\$f" -Force }

# Small VBScript wrapper so clicking the icon does not flash a PowerShell window.
foreach ($pair in @(@('launch.vbs', 'launch.ps1', ''), @('server.vbs', 'launch.ps1', ' -ServerOnly'), @('stop.vbs', 'stop.ps1', ''))) {
    $vbs = 'CreateObject("WScript.Shell").Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""' +
           "$AppHome\$($pair[1])" + '""' + $pair[2] + '", 0, False'
    Set-Content -Path "$AppHome\$($pair[0])" -Value $vbs -Encoding ASCII
}

# ---------- settings (created once, kept on upgrade) ----------
$config = "$AppHome\config.ps1"
if (-not (Test-Path $config)) {
    Step 'Writing settings'
    $chars = [char[]]'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    $secret = -join (1..64 | ForEach-Object { $chars | Get-Random })
    @"
# Maintenance Manager settings. Edit, then restart the app (Stop Maintenance Manager, then open it again).
`$env:CMMS_DB_URL      = 'jdbc:postgresql://localhost:5432/cmms'
`$env:CMMS_DB_USER     = 'cmms'
`$env:CMMS_DB_PASSWORD = 'cmms'
`$env:CMMS_JWT_SECRET  = '$secret'
`$env:CMMS_BIND        = '127.0.0.1'   # use 0.0.0.0 to let other PCs on your network connect
`$env:CMMS_PORT        = '8080'
`$env:CMMS_WEB_DIR     = '$AppHome\app\web'
`$env:CMMS_UPLOAD_DIR  = '$AppHome\data\uploads'
`$env:CMMS_REPORT_DIR  = '$AppHome\data\reports'
`$env:CMMS_ADMIN_PASSWORD = 'admin123'   # only used if no users exist yet
`$JavaW = '$javaw'
"@ | Set-Content -Path $config -Encoding ASCII
} else {
    # keep user settings, but refresh the Java location in case Java was upgraded
    $lines = Get-Content $config | ForEach-Object { if ($_ -like '$JavaW = *') { "`$JavaW = '$javaw'" } else { $_ } }
    $lines | Set-Content -Path $config -Encoding ASCII
}

# ---------- shortcuts ----------
Step 'Creating icons'
$shell = New-Object -ComObject WScript.Shell
function New-Shortcut($path, $vbs, $description) {
    $s = $shell.CreateShortcut($path)
    $s.TargetPath = Join-Path $env:WINDIR 'System32\wscript.exe'
    $s.Arguments = "`"$AppHome\$vbs`""
    $s.WorkingDirectory = $AppHome
    $s.IconLocation = "$AppHome\maintenance.ico,0"
    $s.Description = $description
    $s.Save()
}
New-Shortcut $ShortcutFiles[0] 'launch.vbs' 'Open Maintenance Manager'
New-Shortcut $ShortcutFiles[1] 'launch.vbs' 'Open Maintenance Manager'
New-Shortcut $ShortcutFiles[2] 'stop.vbs' 'Stop the Maintenance Manager server'
if ($StartWithWindows) {
    New-Shortcut $ShortcutFiles[3] 'server.vbs' 'Start the Maintenance Manager server at login'
} elseif (Test-Path $ShortcutFiles[3]) {
    Remove-Item $ShortcutFiles[3] -Force
}

Write-Host ''
Write-Host 'Done!' -ForegroundColor Green
Write-Host "Double-click 'Maintenance Manager' on your Desktop (or find it in the Start menu)."
Write-Host "Settings: $config    Logs: $AppHome\logs"

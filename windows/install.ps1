<#
  Maintenance Manager - one-click Windows installer.
  Started by "Install Maintenance Manager.bat". Safe to run again (upgrades, keeps settings and data).

  Expects the prebuilt app next to this scripts folder:  ..\app\cmms-api.jar  and  ..\app\web\index.html
  (setup.ps1 builds those from source and then calls this script with -Source).

  -StartWithWindows : also start the server automatically at login
  -Uninstall        : remove icons, scheduled tasks and program files (keeps settings, data and the database)
  -NoLaunch         : don't open the app at the end

  Note: this file must stay plain ASCII (Windows PowerShell 5.1 reads BOM-less files as ANSI).
#>
param(
    [string]$Source = (Split-Path $PSScriptRoot -Parent),
    [switch]$StartWithWindows,
    [switch]$Uninstall,
    [switch]$NoLaunch
)
$ErrorActionPreference = 'Stop'
$AppHome = Join-Path $env:LOCALAPPDATA 'MaintenanceManager'
$Config = Join-Path $AppHome 'config.ps1'
$Desktop = [Environment]::GetFolderPath('Desktop')
$Programs = [Environment]::GetFolderPath('Programs')
$Startup = [Environment]::GetFolderPath('Startup')
$Icons = @{
    Desktop = Join-Path $Desktop 'Maintenance Manager.lnk'
    Start   = Join-Path $Programs 'Maintenance Manager.lnk'
    Stop    = Join-Path $Programs 'Stop Maintenance Manager.lnk'
    Login   = Join-Path $Startup 'Maintenance Manager Server.lnk'
}
$Tasks = @('Maintenance Manager Backup', 'Maintenance Manager Alerts', 'Maintenance Manager Monthly Report')
$Total = 7

function Step($n, $msg) { Write-Host ''; Write-Host "[$n/$Total] $msg" -ForegroundColor Cyan }
function Ok($msg) { Write-Host "      $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "      $msg" -ForegroundColor Yellow }
function Fail($msg) { throw $msg }

# PowerShell single-quoted string literal: safe for any path or value (O'Brien, accents...)
function Q([string]$s) { return "'" + $s.Replace("'", "''") + "'" }

# Cryptographically random text (passwords, signing key)
function New-Secret([int]$length) {
    $chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    $bytes = New-Object byte[] $length
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}

# Text files the scripts and Windows Script Host read: UTF-8 with BOM for .ps1, UTF-16 for .vbs,
# so non-English characters in user names / paths survive.
function Write-Utf8Bom($path, $text) { [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($true))) }
function Write-Utf16($path, $text) { [System.IO.File]::WriteAllText($path, $text, [System.Text.Encoding]::Unicode) }

function Stop-App {
    Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" |
        Where-Object { $_.CommandLine -like '*cmms-api.jar*' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

# Returns the path of javaw.exe for Java 21 or newer, or $null.
function Find-JavaW {
    $homes = New-Object System.Collections.Generic.List[string]
    if ($env:JAVA_HOME) { $homes.Add($env:JAVA_HOME) }
    $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($onPath) { $homes.Add((Split-Path (Split-Path $onPath.Source))) }
    foreach ($root in @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Microsoft",
                        "$env:ProgramFiles\Zulu", "$env:ProgramFiles\Amazon Corretto", "$env:LOCALAPPDATA\Programs\Eclipse Adoptium")) {
        if (Test-Path $root) { Get-ChildItem $root -Directory | ForEach-Object { $homes.Add($_.FullName) } }
    }
    foreach ($h in $homes) {
        $javaw = Join-Path $h 'bin\javaw.exe'
        $release = Join-Path $h 'release'
        if (-not (Test-Path $javaw)) { continue }
        $major = 0
        if (Test-Path $release) {
            $line = Select-String -Path $release -Pattern '^JAVA_VERSION="(\d+)' | Select-Object -First 1
            if ($line) { $major = [int]$line.Matches[0].Groups[1].Value }
        }
        if ($major -ge 21) { return $javaw }
    }
    return $null
}

# The PostgreSQL service with the highest version (postgresql-x64-16 beats postgresql-x64-9.6).
function Get-PgService {
    $all = Get-CimInstance Win32_Service | Where-Object { $_.Name -like 'postgresql*' }
    if (-not $all) { return $null }
    return $all | Sort-Object {
        if ($_.Name -match '(\d+)(?:\.(\d+))?\s*$') { [int]$Matches[1] * 100 + [int]("0" + $Matches[2]) } else { 0 }
    } -Descending | Select-Object -First 1
}

function Get-PgBin($svc) {
    if ($svc.PathName -match '^"([^"]+)"') { return Split-Path $Matches[1] }
    return Split-Path (($svc.PathName -split ' ')[0])
}

function Invoke-Winget($id, [string[]]$extra) {
    if (-not (Get-Command winget.exe -ErrorAction SilentlyContinue)) {
        Fail "winget (App Installer) is not available, so $id can't be installed automatically. Install it from the Microsoft Store ('App Installer') or install $id yourself, then run this installer again."
    }
    $wingetArgs = @('install', '-e', '--id', $id, '--accept-package-agreements', '--accept-source-agreements', '--silent') + $extra
    & winget.exe @wingetArgs
    # 0 = installed; -1978335189 = already installed / no newer version
    if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne -1978335189) { Fail "Installing $id failed (winget exit code $LASTEXITCODE)." }
}

# Python 3.10+ for the alert/report jobs (optional). Returns the interpreter path or $null.
function Find-Python {
    $candidates = New-Object System.Collections.Generic.List[string]
    $py = Get-Command py.exe -ErrorAction SilentlyContinue
    if ($py) {
        $ErrorActionPreference = 'Continue'
        $p = & $py.Source -3 -c "import sys; print(sys.executable)" 2>$null
        if ($LASTEXITCODE -eq 0 -and $p) { $candidates.Add(($p | Select-Object -First 1).Trim()) }
    }
    foreach ($root in @("$env:LOCALAPPDATA\Programs\Python", "$env:ProgramFiles")) {
        if (Test-Path $root) {
            Get-ChildItem $root -Directory -Filter 'Python3*' -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending | ForEach-Object { $candidates.Add((Join-Path $_.FullName 'python.exe')) }
        }
    }
    foreach ($c in $candidates) {
        if (-not (Test-Path $c)) { continue }
        $ErrorActionPreference = 'Continue'
        $ok = & $c -c "import sys; print(sys.version_info >= (3, 10))" 2>$null
        if ($LASTEXITCODE -eq 0 -and "$ok".Trim() -eq 'True') { return $c }
    }
    return $null
}

# Settings file: written from the current values, so upgrades add new settings but keep yours.
function Write-Config($v) {
    $text = @"
# Maintenance Manager settings. After editing: Start menu > Stop Maintenance Manager, then open it again.
`$env:CMMS_DB_URL         = $(Q $v.DbUrl)
`$env:CMMS_DB_USER        = $(Q $v.DbUser)
`$env:CMMS_DB_PASSWORD    = $(Q $v.DbPassword)
`$env:CMMS_JWT_SECRET     = $(Q $v.JwtSecret)
`$env:CMMS_BIND           = $(Q $v.Bind)   # use 0.0.0.0 to let other PCs on your network connect
`$env:CMMS_PORT           = $(Q $v.Port)
`$env:CMMS_WEB_DIR        = $(Q "$AppHome\app\web")
`$env:CMMS_UPLOAD_DIR     = $(Q "$AppHome\data\uploads")
`$env:CMMS_REPORT_DIR     = $(Q "$AppHome\data\reports")
`$env:CMMS_ADMIN_PASSWORD = $(Q $v.AdminPassword)   # first 'admin' password; only used when there are no users yet
`$env:CMMS_PM_SCHEDULER   = 'true'   # create PM work orders at 02:00 and 12:00 while the server is running
# Email alerts (leave CMMS_SMTP_HOST empty to turn them off)
`$env:CMMS_APP_URL        = $(Q $v.AppUrl)
`$env:CMMS_SMTP_HOST      = $(Q $v.SmtpHost)
`$env:CMMS_SMTP_PORT      = $(Q $v.SmtpPort)
`$env:CMMS_SMTP_USER      = $(Q $v.SmtpUser)
`$env:CMMS_SMTP_PASSWORD  = $(Q $v.SmtpPassword)
`$env:CMMS_SMTP_FROM      = $(Q $v.SmtpFrom)
`$env:CMMS_SMTP_STARTTLS  = $(Q $v.SmtpStartTls)
`$JavaW = $(Q $v.JavaW)
`$PgBin = $(Q $v.PgBin)
"@
    Write-Utf8Bom $Config $text
}

function Read-Value($name, $default) {
    $val = [Environment]::GetEnvironmentVariable($name)
    if ($null -eq $val -or $val -eq '') { return $default } else { return $val }
}

try {
    Write-Host '=============================================' -ForegroundColor Cyan
    Write-Host '   Maintenance Manager - Windows installer' -ForegroundColor Cyan
    Write-Host '============================================='

    if ($Uninstall) {
        Stop-App
        Start-Sleep -Seconds 2
        $Icons.Values | Where-Object { Test-Path $_ } | ForEach-Object { Remove-Item $_ -Force }
        $ErrorActionPreference = 'Continue'
        foreach ($t in $Tasks) { schtasks.exe /Delete /TN $t /F 2>$null | Out-Null }
        $ErrorActionPreference = 'Stop'
        foreach ($d in @("$AppHome\app", "$AppHome\worker")) { if (Test-Path $d) { Remove-Item $d -Recurse -Force } }
        Write-Host "Removed. Settings, backups and uploaded files are still in $AppHome; the database was not touched."
        exit 0
    }

    # ---------------------------------------------------------------- 1
    Step 1 'Checking the download'
    $jar = Join-Path $Source 'app\cmms-api.jar'
    $web = Join-Path $Source 'app\web'
    if (-not (Test-Path $jar) -or -not (Test-Path (Join-Path $web 'index.html'))) {
        Fail "The program files were not found next to this installer ($Source\app). Right-click the downloaded zip, choose 'Extract All...', open the extracted folder and double-click 'Install Maintenance Manager' there."
    }
    Ok 'Program files found'

    # ---------------------------------------------------------------- 2
    Step 2 'Java 21'
    $javaw = Find-JavaW
    if (-not $javaw) {
        Write-Host '      Installing Java 21 (click Yes if Windows asks)...'
        Invoke-Winget 'EclipseAdoptium.Temurin.21.JRE' @()
        $javaw = Find-JavaW
        if (-not $javaw) { Fail 'Java 21 was installed but could not be found. Restart the PC and run this installer again.' }
    }
    Ok "Using $javaw"

    # ---------------------------------------------------------------- 3
    Step 3 'PostgreSQL database server'
    $svc = Get-PgService
    if (-not $svc) {
        Write-Host '      Installing PostgreSQL 16 (this takes a few minutes; click Yes if Windows asks)...'
        $superPw = New-Secret 24
        Invoke-Winget 'PostgreSQL.PostgreSQL.16' @('--override', "--mode unattended --unattendedmodeui none --superpassword $superPw --servicename postgresql-x64-16 --serverport 5432")
        $svc = Get-PgService
        if (-not $svc) { Fail 'PostgreSQL was installed but its service was not found. Restart the PC and run this installer again.' }
    }
    if ($svc.State -ne 'Running') {
        try { Start-Service $svc.Name } catch { Write-Host '      (PostgreSQL will be started in the next step)' }
    }
    $pgBin = Get-PgBin $svc
    Ok "Service: $($svc.Name)"

    # ---------------------------------------------------------------- 4
    Step 4 'Settings'
    New-Item -ItemType Directory -Force -Path $AppHome | Out-Null
    $firstInstall = -not (Test-Path $Config)
    if (-not $firstInstall) { . $Config }   # keep existing values
    $settings = @{
        DbUrl         = Read-Value 'CMMS_DB_URL' 'jdbc:postgresql://localhost:5432/cmms'
        DbUser        = Read-Value 'CMMS_DB_USER' 'cmms'
        DbPassword    = Read-Value 'CMMS_DB_PASSWORD' (New-Secret 24)
        JwtSecret     = Read-Value 'CMMS_JWT_SECRET' (New-Secret 64)
        Bind          = Read-Value 'CMMS_BIND' '127.0.0.1'
        Port          = Read-Value 'CMMS_PORT' '8080'
        AdminPassword = Read-Value 'CMMS_ADMIN_PASSWORD' (New-Secret 12)
        AppUrl        = Read-Value 'CMMS_APP_URL' 'http://localhost:8080'
        SmtpHost      = Read-Value 'CMMS_SMTP_HOST' ''
        SmtpPort      = Read-Value 'CMMS_SMTP_PORT' '587'
        SmtpUser      = Read-Value 'CMMS_SMTP_USER' ''
        SmtpPassword  = Read-Value 'CMMS_SMTP_PASSWORD' ''
        SmtpFrom      = Read-Value 'CMMS_SMTP_FROM' 'maintenance@localhost'
        SmtpStartTls  = Read-Value 'CMMS_SMTP_STARTTLS' 'true'
        JavaW         = $javaw
        PgBin         = $pgBin
    }
    if ($settings.DbUrl -notmatch '/([A-Za-z_][A-Za-z0-9_]*)(\?.*)?$') { Fail "Unexpected CMMS_DB_URL in settings: $($settings.DbUrl)" }
    $dbName = $Matches[1]
    Write-Config $settings
    . $Config
    if ($firstInstall) { Ok "Created $Config" } else { Ok "Kept your settings in $Config" }

    # ---------------------------------------------------------------- 5
    Step 5 'Setting up the database (click Yes when Windows asks for permission)'
    $result = Join-Path $env:TEMP ("mm-db-" + [guid]::NewGuid().ToString('N') + '.txt')
    $request = Join-Path $env:TEMP ("mm-req-" + [guid]::NewGuid().ToString('N') + '.txt')
    # the request (incl. the DB password) goes in a file only this user can read, not on a command line
    Write-Utf8Bom $request (@($svc.Name, $dbName, $settings.DbUser, $settings.DbPassword) -join "`r`n")
    $dbScript = Join-Path $PSScriptRoot 'db-setup.ps1'
    try {
        Start-Process -FilePath 'powershell.exe' -Verb RunAs -Wait -WindowStyle Hidden `
            -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$dbScript`" -RequestFile `"$request`" -ResultFile `"$result`""
    } catch {
        Fail 'Administrator permission is needed once to set up the database. Run the installer again and click Yes.'
    } finally {
        Remove-Item $request -Force -ErrorAction SilentlyContinue
    }
    $dbStatus = if (Test-Path $result) { (Get-Content $result -Raw -Encoding UTF8).Trim() } else { 'ERROR no result from database setup' }
    Remove-Item $result -Force -ErrorAction SilentlyContinue
    if ($dbStatus -notlike 'OK*') { Fail ("Database setup failed: " + $dbStatus.Substring([Math]::Min(6, $dbStatus.Length))) }
    $newDatabase = ($dbStatus -eq 'OK NEW')
    Ok "Database `"$dbName`" is ready"

    # ---------------------------------------------------------------- 6
    Step 6 'Installing Maintenance Manager'
    Stop-App
    Start-Sleep -Seconds 1
    foreach ($d in @("$AppHome\app", "$AppHome\logs", "$AppHome\data\uploads", "$AppHome\data\reports", "$AppHome\backups")) {
        New-Item -ItemType Directory -Force -Path $d | Out-Null
    }
    Copy-Item $jar "$AppHome\app\cmms-api.jar" -Force
    if (Test-Path "$AppHome\app\web") { Remove-Item "$AppHome\app\web" -Recurse -Force }
    Copy-Item $web "$AppHome\app\web" -Recurse
    foreach ($f in @('launch.ps1', 'stop.ps1', 'backup.ps1', 'worker.ps1', 'maintenance.ico')) {
        Copy-Item (Join-Path $PSScriptRoot $f) "$AppHome\$f" -Force
    }

    # Tiny VBScript wrappers so icons and scheduled jobs don't flash a PowerShell window
    $wrappers = @(
        @('launch.vbs', 'launch.ps1', ''), @('server.vbs', 'launch.ps1', ' -ServerOnly'), @('stop.vbs', 'stop.ps1', ''),
        @('task-backup.vbs', 'backup.ps1', ''), @('task-alerts.vbs', 'worker.ps1', ' alerts'), @('task-report.vbs', 'worker.ps1', ' report'))
    foreach ($w in $wrappers) {
        $vbs = 'CreateObject("WScript.Shell").Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""' +
               "$AppHome\$($w[1])" + '""' + $w[2] + '", 0, False'
        Write-Utf16 "$AppHome\$($w[0])" $vbs
    }
    Get-ChildItem $AppHome -Recurse -File -ErrorAction SilentlyContinue | Unblock-File -ErrorAction SilentlyContinue
    Ok "Installed to $AppHome"

    # ---------------------------------------------------------------- 7
    Step 7 'Icons and scheduled jobs'
    $shell = New-Object -ComObject WScript.Shell
    function New-Icon($path, $vbs, $description) {
        $s = $shell.CreateShortcut($path)
        $s.TargetPath = Join-Path $env:WINDIR 'System32\wscript.exe'
        $s.Arguments = "`"$AppHome\$vbs`""
        $s.WorkingDirectory = $AppHome
        $s.IconLocation = "$AppHome\maintenance.ico,0"
        $s.Description = $description
        $s.Save()
    }
    New-Icon $Icons.Desktop 'launch.vbs' 'Open Maintenance Manager'
    New-Icon $Icons.Start 'launch.vbs' 'Open Maintenance Manager'
    New-Icon $Icons.Stop 'stop.vbs' 'Stop the Maintenance Manager server'
    if ($StartWithWindows) { New-Icon $Icons.Login 'server.vbs' 'Start the Maintenance Manager server at login' }
    Ok "Desktop icon: $($Icons.Desktop)"

    function New-Task($name, $vbs, [string[]]$schedule) {
        $ErrorActionPreference = 'Continue'
        $tr = "wscript.exe `"$AppHome\$vbs`""
        $out = schtasks.exe /Create /TN $name /TR $tr @schedule /F 2>&1
        $ErrorActionPreference = 'Stop'
        if ($LASTEXITCODE -ne 0) { Warn "Could not create scheduled job '$name': $out" } else { Ok "Scheduled: $name" }
    }
    New-Task $Tasks[0] 'task-backup.vbs' @('/SC', 'DAILY', '/ST', '01:30')

    # optional Python jobs (email alerts, monthly PDF/Excel report)
    $workerSrc = Join-Path $Source 'worker'
    $python = $null
    if (Test-Path (Join-Path $workerSrc 'cmms_worker')) { $python = Find-Python }
    if ($python) {
        try {
            $wdir = Join-Path $AppHome 'worker'
            New-Item -ItemType Directory -Force -Path $wdir | Out-Null
            if (Test-Path "$wdir\cmms_worker") { Remove-Item "$wdir\cmms_worker" -Recurse -Force }
            Copy-Item (Join-Path $workerSrc 'cmms_worker') "$wdir\cmms_worker" -Recurse
            Copy-Item (Join-Path $workerSrc 'requirements.txt') "$wdir\requirements.txt" -Force
            $ErrorActionPreference = 'Continue'
            if (-not (Test-Path "$wdir\.venv\Scripts\python.exe")) { & $python -m venv "$wdir\.venv" 2>&1 | Out-Null }
            & "$wdir\.venv\Scripts\python.exe" -m pip install --quiet --disable-pip-version-check -r "$wdir\requirements.txt" 2>&1 | Out-Null
            $pipOk = ($LASTEXITCODE -eq 0)
            $ErrorActionPreference = 'Stop'
            if (-not $pipOk) { throw 'pip could not install the Python packages (internet needed)' }
            New-Task $Tasks[1] 'task-alerts.vbs' @('/SC', 'MINUTE', '/MO', '15')
            New-Task $Tasks[2] 'task-report.vbs' @('/SC', 'MONTHLY', '/D', '1', '/ST', '03:00')
        } catch {
            Warn "Email alerts and monthly reports were not set up: $($_.Exception.Message)"
        }
    } else {
        Warn 'Python 3.10+ not found: email alerts and monthly PDF/Excel reports are off (everything else works).'
        Warn 'To turn them on later: install Python from python.org, then run this installer again.'
    }

    Write-Host ''
    Write-Host '=============================================' -ForegroundColor Green
    Write-Host '   Installation complete!' -ForegroundColor Green
    Write-Host '============================================='
    Write-Host 'Open it any time with the "Maintenance Manager" icon on your Desktop or in the Start menu.'
    Write-Host 'PM work orders are created automatically while the server is running (02:00 and 12:00).'
    if ($newDatabase) {
        Write-Host ''
        Write-Host "First login:   username  admin    password  $($settings.AdminPassword)" -ForegroundColor Yellow
        Write-Host '(you will be asked to choose a new password)' -ForegroundColor Yellow
    }
    if (-not $NoLaunch) {
        Write-Host ''
        Write-Host 'Starting Maintenance Manager now...'
        Start-Process -FilePath (Join-Path $env:WINDIR 'System32\wscript.exe') -ArgumentList "`"$AppHome\launch.vbs`""
    }
    exit 0
} catch {
    Write-Host ''
    Write-Host 'INSTALL STOPPED' -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    Write-Host ''
    Write-Host 'Fix the problem above and double-click the installer again (it is safe to re-run).'
    Write-Host 'If you are stuck, copy the red text and send it to whoever is helping you.'
    exit 1
}

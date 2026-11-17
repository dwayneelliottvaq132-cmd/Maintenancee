<#
  Maintenance Manager - one-click Windows installer.
  Started by "Install Maintenance Manager.bat". Safe to run again (upgrades, keeps settings and data).

  Expects the prebuilt app next to this scripts folder:  ..\app\cmms-api.jar  and  ..\app\web\index.html
  (setup.ps1 builds those from source and then calls this script with -Source).

  -StartWithWindows : also start the server automatically at login
  -Uninstall        : remove icons and program files (keeps settings, data and the database)
  -NoLaunch         : don't open the app at the end
#>
param(
    [string]$Source = (Split-Path $PSScriptRoot -Parent),
    [switch]$StartWithWindows,
    [switch]$Uninstall,
    [switch]$NoLaunch
)
$ErrorActionPreference = 'Stop'
$AppHome = Join-Path $env:LOCALAPPDATA 'MaintenanceManager'
$Desktop = [Environment]::GetFolderPath('Desktop')
$Programs = [Environment]::GetFolderPath('Programs')
$Startup = [Environment]::GetFolderPath('Startup')
$Icons = @{
    Desktop = Join-Path $Desktop 'Maintenance Manager.lnk'
    Start   = Join-Path $Programs 'Maintenance Manager.lnk'
    Stop    = Join-Path $Programs 'Stop Maintenance Manager.lnk'
    Login   = Join-Path $Startup 'Maintenance Manager Server.lnk'
}

function Step($n, $msg) { Write-Host ''; Write-Host "[$n/6] $msg" -ForegroundColor Cyan }
function Ok($msg) { Write-Host "      $msg" -ForegroundColor Green }
function Fail($msg) { throw $msg }

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

function Invoke-Winget($id, [string[]]$extra) {
    if (-not (Get-Command winget.exe -ErrorAction SilentlyContinue)) {
        Fail "winget (App Installer) is not available, so $id can't be installed automatically. Install it from the Microsoft Store ('App Installer') or install $id yourself, then run this installer again."
    }
    $wingetArgs = @('install', '-e', '--id', $id, '--accept-package-agreements', '--accept-source-agreements', '--silent') + $extra
    & winget.exe @wingetArgs
    # 0 = installed; -1978335189 = already installed / no newer version
    if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne -1978335189) { Fail "Installing $id failed (winget exit code $LASTEXITCODE)." }
}

try {
    Write-Host '=============================================' -ForegroundColor Cyan
    Write-Host '   Maintenance Manager - Windows installer' -ForegroundColor Cyan
    Write-Host '============================================='

    if ($Uninstall) {
        Stop-App
        Start-Sleep -Seconds 2
        $Icons.Values | Where-Object { Test-Path $_ } | ForEach-Object { Remove-Item $_ -Force }
        if (Test-Path "$AppHome\app") { Remove-Item "$AppHome\app" -Recurse -Force }
        Write-Host "Removed. Settings and uploaded files are still in $AppHome; the database was not touched."
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
    $svc = Get-Service -Name 'postgresql*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $svc) {
        Write-Host '      Installing PostgreSQL 16 (this takes a few minutes; click Yes if Windows asks)...'
        $chars = [char[]]'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
        $superPw = -join (1..20 | ForEach-Object { $chars | Get-Random })
        Invoke-Winget 'PostgreSQL.PostgreSQL.16' @('--override', "--mode unattended --unattendedmodeui none --superpassword $superPw --servicename postgresql-x64-16 --serverport 5432")
        $svc = Get-Service -Name 'postgresql*' -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $svc) { Fail 'PostgreSQL was installed but its service was not found. Restart the PC and run this installer again.' }
    }
    if ($svc.Status -ne 'Running') {
        try { Start-Service $svc.Name } catch { Write-Host '      (PostgreSQL will be started in the next step)' }
    }
    Ok "Service: $($svc.Name)"

    # ---------------------------------------------------------------- 4
    Step 4 'Setting up the database (click Yes when Windows asks for permission)'
    $result = Join-Path $env:TEMP ("mm-db-" + [guid]::NewGuid().ToString('N') + '.txt')
    $dbScript = Join-Path $PSScriptRoot 'db-setup.ps1'
    try {
        Start-Process -FilePath 'powershell.exe' -Verb RunAs -Wait -WindowStyle Hidden `
            -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$dbScript`" -ResultFile `"$result`""
    } catch {
        Fail 'Administrator permission is needed once to set up the database. Run the installer again and click Yes.'
    }
    $dbStatus = if (Test-Path $result) { (Get-Content $result -Raw).Trim() } else { 'ERROR no result from database setup' }
    Remove-Item $result -Force -ErrorAction SilentlyContinue
    if ($dbStatus -notlike 'OK*') { Fail ("Database setup failed: " + $dbStatus.Substring([Math]::Min(6, $dbStatus.Length))) }
    $newDatabase = ($dbStatus -eq 'OK NEW')
    Ok 'Database "cmms" is ready'

    # ---------------------------------------------------------------- 5
    Step 5 'Installing Maintenance Manager'
    Stop-App
    Start-Sleep -Seconds 1
    foreach ($d in @($AppHome, "$AppHome\app", "$AppHome\logs", "$AppHome\data\uploads", "$AppHome\data\reports")) {
        New-Item -ItemType Directory -Force -Path $d | Out-Null
    }
    Copy-Item $jar "$AppHome\app\cmms-api.jar" -Force
    if (Test-Path "$AppHome\app\web") { Remove-Item "$AppHome\app\web" -Recurse -Force }
    Copy-Item $web "$AppHome\app\web" -Recurse
    foreach ($f in @('launch.ps1', 'stop.ps1', 'maintenance.ico')) { Copy-Item (Join-Path $PSScriptRoot $f) "$AppHome\$f" -Force }

    # VBScript wrappers so the icons don't flash a PowerShell window
    $wrappers = @(@('launch.vbs', 'launch.ps1', ''), @('server.vbs', 'launch.ps1', ' -ServerOnly'), @('stop.vbs', 'stop.ps1', ''))
    foreach ($w in $wrappers) {
        $vbs = 'CreateObject("WScript.Shell").Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""' +
               "$AppHome\$($w[1])" + '""' + $w[2] + '", 0, False'
        Set-Content -Path "$AppHome\$($w[0])" -Value $vbs -Encoding ASCII
    }

    # Settings: written once, kept on upgrade (Java path refreshed)
    $config = "$AppHome\config.ps1"
    if (-not (Test-Path $config)) {
        $chars = [char[]]'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
        $secret = -join (1..64 | ForEach-Object { $chars | Get-Random })
        @"
# Maintenance Manager settings. After editing: Start menu > Stop Maintenance Manager, then open it again.
`$env:CMMS_DB_URL      = 'jdbc:postgresql://localhost:5432/cmms'
`$env:CMMS_DB_USER     = 'cmms'
`$env:CMMS_DB_PASSWORD = 'cmms'
`$env:CMMS_JWT_SECRET  = '$secret'
`$env:CMMS_BIND        = '127.0.0.1'   # use 0.0.0.0 to let other PCs on your network connect
`$env:CMMS_PORT        = '8080'
`$env:CMMS_WEB_DIR     = '$AppHome\app\web'
`$env:CMMS_UPLOAD_DIR  = '$AppHome\data\uploads'
`$env:CMMS_REPORT_DIR  = '$AppHome\data\reports'
`$env:CMMS_ADMIN_PASSWORD = 'admin123'   # only used when there are no users yet
`$JavaW = '$javaw'
"@ | Set-Content -Path $config -Encoding ASCII
    } else {
        $lines = Get-Content $config | ForEach-Object { if ($_ -like '$JavaW = *') { "`$JavaW = '$javaw'" } else { $_ } }
        $lines | Set-Content -Path $config -Encoding ASCII
    }
    Get-ChildItem $AppHome -Recurse -File | Unblock-File -ErrorAction SilentlyContinue
    Ok "Installed to $AppHome"

    # ---------------------------------------------------------------- 6
    Step 6 'Creating icons'
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
    Ok 'Start menu: Maintenance Manager, Stop Maintenance Manager'

    Write-Host ''
    Write-Host '=============================================' -ForegroundColor Green
    Write-Host '   Installation complete!' -ForegroundColor Green
    Write-Host '============================================='
    Write-Host 'Open it any time with the "Maintenance Manager" icon on your Desktop or in the Start menu.'
    if ($newDatabase) {
        Write-Host ''
        Write-Host 'First login:   username  admin    password  admin123' -ForegroundColor Yellow
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

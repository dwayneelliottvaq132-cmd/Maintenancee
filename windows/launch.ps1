<#
  Opens Maintenance Manager: starts the server in the background if it is not running,
  shows a "Starting..." window while it boots, then opens the app in its own window.
  -ServerOnly : just make sure the server is running (used at Windows login).

  Health checks use 127.0.0.1, not "localhost": the server listens on IPv4 only, and on Windows
  "localhost" tries IPv6 (::1) first, which can eat a short timeout before IPv4 is tried.
#>
param([switch]$ServerOnly)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms, System.Drawing

$AppHome = $PSScriptRoot
. (Join-Path $AppHome 'config.ps1')
$port = if ($env:CMMS_PORT) { $env:CMMS_PORT } else { '8080' }
$healthUrl = "http://127.0.0.1:$port/api/actuator/health"
$appUrl = "http://localhost:$port"
$log = Join-Path $AppHome 'logs\api.log'
$errLog = Join-Path $AppHome 'logs\api-error.log'
$giveUpSeconds = 150

function Test-Up {
    try {
        $req = [System.Net.WebRequest]::Create($healthUrl)
        $req.Timeout = 5000
        $req.Proxy = $null
        $resp = $req.GetResponse()
        $body = (New-Object System.IO.StreamReader($resp.GetResponseStream())).ReadToEnd()
        $resp.Close()
        return ($body -match '"status"\s*:\s*"UP"')
    } catch { return $false }
}
function Test-Running {
    return [bool](Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" |
        Where-Object { $_.CommandLine -like '*cmms-api.jar*' })
}
function Show-Error($text) {
    if (-not $ServerOnly) {
        [System.Windows.Forms.MessageBox]::Show($text, 'Maintenance Manager', 'OK', 'Error') | Out-Null
    }
}
function Open-App {
    $candidates = @(
        "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
        "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
        "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
        "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
        "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe"
    )
    $browser = $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
    if ($browser) { Start-Process -FilePath $browser -ArgumentList @("--app=$appUrl", '--window-size=1400,900') }
    else { Start-Process $appUrl }
}

# Already running: just open it.
if (Test-Up) {
    if (-not $ServerOnly) { Open-App }
    exit 0
}

# Start the server unless one is already booting.
if (-not (Test-Running)) {
    if (Test-Path $log) { Move-Item $log "$log.previous" -Force }
    Start-Process -FilePath $JavaW -WindowStyle Hidden -WorkingDirectory $AppHome `
        -ArgumentList @('-Xms128m', '-Xmx768m', '-jar', "`"$AppHome\app\cmms-api.jar`"") `
        -RedirectStandardOutput $log -RedirectStandardError $errLog
}

if ($ServerOnly) {
    $deadline = (Get-Date).AddSeconds($giveUpSeconds)
    while ((Get-Date) -lt $deadline -and -not (Test-Up)) { Start-Sleep -Seconds 2 }
    exit 0
}

# ---- background checker: keeps the window responsive while we wait ----
$sync = [hashtable]::Synchronized(@{ Ready = $false; Dead = $false; Stop = $false })
$checker = [powershell]::Create()
[void]$checker.AddScript({
    param($sync, $healthUrl)
    $deadCount = 0
    while (-not $sync.Stop) {
        try {
            $req = [System.Net.WebRequest]::Create($healthUrl)
            $req.Timeout = 5000
            $req.Proxy = $null
            $resp = $req.GetResponse()
            $body = (New-Object System.IO.StreamReader($resp.GetResponseStream())).ReadToEnd()
            $resp.Close()
            if ($body -match '"status"\s*:\s*"UP"') { $sync.Ready = $true; break }
        } catch { }
        $alive = Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" |
            Where-Object { $_.CommandLine -like '*cmms-api.jar*' }
        if ($alive) { $deadCount = 0 } else { $deadCount++ }
        if ($deadCount -ge 3) { $sync.Dead = $true; break }   # server exited during startup
        Start-Sleep -Seconds 1
    }
}).AddArgument($sync).AddArgument($healthUrl)
$handle = $checker.BeginInvoke()

# ---- "Starting..." window ----
[System.Windows.Forms.Application]::EnableVisualStyles()
$form = New-Object System.Windows.Forms.Form
$form.Text = 'Maintenance Manager'
$form.Size = New-Object System.Drawing.Size(400, 170)
$form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox = $false
$form.MinimizeBox = $false
$form.TopMost = $true
$ico = Join-Path $AppHome 'maintenance.ico'
if (Test-Path $ico) { $form.Icon = New-Object System.Drawing.Icon($ico) }
$label = New-Object System.Windows.Forms.Label
$label.Text = 'Starting Maintenance Manager...'
$label.AutoSize = $true
$label.Font = New-Object System.Drawing.Font('Segoe UI', 11)
$label.Location = New-Object System.Drawing.Point(20, 18)
$bar = New-Object System.Windows.Forms.ProgressBar
$bar.Style = 'Marquee'
$bar.MarqueeAnimationSpeed = 30
$bar.Location = New-Object System.Drawing.Point(20, 55)
$bar.Size = New-Object System.Drawing.Size(345, 18)
$status = New-Object System.Windows.Forms.Label
$status.AutoSize = $true
$status.ForeColor = [System.Drawing.Color]::DimGray
$status.Location = New-Object System.Drawing.Point(20, 85)
$status.Text = 'This usually takes 20-40 seconds.'
$form.Controls.AddRange(@($label, $bar, $status))

$started = Get-Date
$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 500
$timer.Add_Tick({
    $secs = [int]((Get-Date) - $started).TotalSeconds
    $status.Text = "Waiting for the server... ($secs s)  This usually takes 20-40 seconds."
    if ($sync.Ready -or $sync.Dead -or $secs -gt $giveUpSeconds) { $timer.Stop(); $form.Close() }
})
$form.Add_Shown({ $timer.Start() })
[void]$form.ShowDialog()

$sync.Stop = $true
try { $checker.EndInvoke($handle) | Out-Null } catch { }
$checker.Dispose()

if (-not $sync.Ready) {
    $hint = ''
    $text = ((Get-Content $log -Tail 200 -ErrorAction SilentlyContinue) + (Get-Content $errLog -Tail 50 -ErrorAction SilentlyContinue)) -join "`n"
    if ($text -match 'password authentication failed|Connection to localhost:5432 refused|database "cmms" does not exist') {
        $hint = "`n`nIt could not connect to the PostgreSQL database. Check that PostgreSQL is running and that the database settings in config.ps1 are right."
    } elseif ($text -match 'Port \d+ was already in use|Address already in use') {
        $hint = "`n`nAnother program is using port $port. Change CMMS_PORT in config.ps1."
    }
    Show-Error ("Maintenance Manager did not start.$hint`n`nDetails are in:`n$log")
    exit 1
}

Open-App

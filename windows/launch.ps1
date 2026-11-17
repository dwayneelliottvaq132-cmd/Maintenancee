<#
  Opens Maintenance Manager: starts the server in the background if it is not running,
  shows a "Starting..." window while it boots, then opens the app in its own window.
  -ServerOnly : just make sure the server is running (used at Windows login).
#>
param([switch]$ServerOnly)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms, System.Drawing

$AppHome = $PSScriptRoot
. (Join-Path $AppHome 'config.ps1')
$port = if ($env:CMMS_PORT) { $env:CMMS_PORT } else { '8080' }
$url = "http://localhost:$port"
$log = Join-Path $AppHome 'logs\api.log'

function Test-Up {
    try { return ((Invoke-RestMethod "$url/api/actuator/health" -TimeoutSec 2).status -eq 'UP') } catch { return $false }
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

if (-not (Test-Up)) {
    if (-not (Test-Running)) {
        # keep the previous log for troubleshooting
        if (Test-Path $log) { Move-Item $log "$log.previous" -Force }
        Start-Process -FilePath $JavaW -WindowStyle Hidden -WorkingDirectory $AppHome `
            -ArgumentList @('-Xms128m', '-Xmx768m', '-jar', "`"$AppHome\app\cmms-api.jar`"") `
            -RedirectStandardOutput $log -RedirectStandardError (Join-Path $AppHome 'logs\api-error.log')
    }

    if ($ServerOnly) {
        for ($i = 0; $i -lt 120 -and -not (Test-Up); $i++) { Start-Sleep -Seconds 1 }
        exit 0
    }

    # "Starting..." window that closes itself when the server answers
    $form = New-Object System.Windows.Forms.Form
    $form.Text = 'Maintenance Manager'
    $form.Size = New-Object System.Drawing.Size(380, 150)
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
    $label.Location = New-Object System.Drawing.Point(20, 20)
    $bar = New-Object System.Windows.Forms.ProgressBar
    $bar.Style = 'Marquee'
    $bar.MarqueeAnimationSpeed = 30
    $bar.Location = New-Object System.Drawing.Point(20, 60)
    $bar.Size = New-Object System.Drawing.Size(325, 18)
    $form.Controls.AddRange(@($label, $bar))

    $script:ready = $false
    $script:started = Get-Date
    $timer = New-Object System.Windows.Forms.Timer
    $timer.Interval = 1000
    $timer.Add_Tick({
        if (Test-Up) { $script:ready = $true; $timer.Stop(); $form.Close() }
        elseif (((Get-Date) - $script:started).TotalSeconds -gt 150) { $timer.Stop(); $form.Close() }
        elseif (-not (Test-Running)) { $timer.Stop(); $form.Close() }   # server crashed on startup
    })
    $form.Add_Shown({ $timer.Start() })
    [void]$form.ShowDialog()

    if (-not $script:ready) {
        $hint = ''
        $errLog = Join-Path $AppHome 'logs\api-error.log'
        $text = ((Get-Content $log -Tail 200 -ErrorAction SilentlyContinue) + (Get-Content $errLog -Tail 50 -ErrorAction SilentlyContinue)) -join "`n"
        if ($text -match 'password authentication failed|Connection to localhost:5432 refused|database "cmms" does not exist') {
            $hint = "`n`nIt could not connect to the PostgreSQL database. Check that PostgreSQL is running and that the database settings in config.ps1 are right."
        } elseif ($text -match 'Port \d+ was already in use|Address already in use') {
            $hint = "`n`nAnother program is using port $port. Change CMMS_PORT in config.ps1."
        }
        Show-Error ("Maintenance Manager did not start.$hint`n`nDetails are in:`n$log")
        exit 1
    }
}

if ($ServerOnly) { exit 0 }

# Open in its own app window (Microsoft Edge / Chrome app mode), otherwise the default browser.
$candidates = @(
    "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
    "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
    "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
    "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
    "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe"
)
$browser = $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if ($browser) {
    Start-Process -FilePath $browser -ArgumentList @("--app=$url", '--window-size=1400,900')
} else {
    Start-Process $url
}

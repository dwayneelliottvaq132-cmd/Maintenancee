<#
  Creates (or repairs) the "cmms" PostgreSQL user and database without needing the postgres password.
  Must run as Administrator; install.ps1 starts it elevated.

  How: temporarily allows password-less local connections (pg_hba.conf -> trust), runs the SQL,
  then ALWAYS puts the original pg_hba.conf back and restarts PostgreSQL.
  Writes "OK NEW", "OK EXISTING" or "ERROR <message>" to -ResultFile.
#>
param(
    [Parameter(Mandatory = $true)][string]$ResultFile,
    [string]$DbName = 'cmms',
    [string]$DbUser = 'cmms',
    [string]$DbPassword = 'cmms'
)
$ErrorActionPreference = 'Stop'

function Write-Result($text) { Set-Content -Path $ResultFile -Value $text -Encoding ASCII }

try {
    # ---- locate the PostgreSQL service, its programs and its data folder ----
    $svc = Get-CimInstance Win32_Service | Where-Object { $_.Name -like 'postgresql*' } |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $svc) { throw 'No PostgreSQL service was found on this PC.' }
    $image = $svc.PathName   # e.g. "C:\...\bin\pg_ctl.exe" runservice -N "postgresql-x64-16" -D "C:\...\data" -w
    if ($image -match '^"([^"]+)"') { $pgCtl = $Matches[1] } else { $pgCtl = ($image -split ' ')[0] }
    $bin = Split-Path $pgCtl
    if ($image -match '-D\s+"([^"]+)"') { $data = $Matches[1] }
    elseif ($image -match '-D\s+(\S+)') { $data = $Matches[1] }
    else { throw "Could not find the PostgreSQL data folder in: $image" }
    $psql = Join-Path $bin 'psql.exe'
    $isReady = Join-Path $bin 'pg_isready.exe'
    $hba = Join-Path $data 'pg_hba.conf'
    foreach ($f in @($psql, $hba)) { if (-not (Test-Path $f)) { throw "Not found: $f" } }

    if ($svc.State -ne 'Running') { Start-Service -Name $svc.Name }

    function Wait-Ready {
        $ErrorActionPreference = 'Continue'   # PowerShell 5.1 treats native stderr as errors under 'Stop'
        for ($i = 0; $i -lt 60; $i++) {
            & $isReady -h 127.0.0.1 -q 2>$null
            if ($LASTEXITCODE -eq 0) { return }
            Start-Sleep -Seconds 1
        }
        throw 'PostgreSQL did not come back up after restarting.'
    }

    # ---- temporarily trust local connections ----
    $backup = "$hba.mm-backup"
    Copy-Item $hba $backup -Force
    try {
        $lines = Get-Content $hba | ForEach-Object {
            if ($_ -match '^\s*(host|local)\s' -and $_ -match '(scram-sha-256|md5|password)\s*$') {
                $_ -replace '(scram-sha-256|md5|password)\s*$', 'trust'
            } else { $_ }
        }
        $lines | Set-Content -Path $hba -Encoding ASCII
        Restart-Service -Name $svc.Name -Force
        Wait-Ready

        function Invoke-Sql($sql, $db = 'postgres') {
            $ErrorActionPreference = 'Continue'
            $out = & $psql -h 127.0.0.1 -U postgres -d $db -v ON_ERROR_STOP=1 -tAc $sql 2>&1
            if ($LASTEXITCODE -ne 0) { throw "SQL failed: $sql`n$out" }
            return ($out | Out-String).Trim()
        }

        $pw = $DbPassword.Replace("'", "''")
        if ((Invoke-Sql "SELECT 1 FROM pg_roles WHERE rolname = '$DbUser'") -eq '1') {
            Invoke-Sql "ALTER ROLE $DbUser WITH LOGIN PASSWORD '$pw'" | Out-Null
        } else {
            Invoke-Sql "CREATE ROLE $DbUser LOGIN PASSWORD '$pw'" | Out-Null
        }
        $new = $false
        if ((Invoke-Sql "SELECT 1 FROM pg_database WHERE datname = '$DbName'") -ne '1') {
            Invoke-Sql "CREATE DATABASE $DbName OWNER $DbUser" | Out-Null
            $new = $true
        }
        # a database that already has the app's tables but no users is also "new" for the first login message
        $hasUsers = Invoke-Sql "SELECT to_regclass('public.users') IS NOT NULL" $DbName
        if ($hasUsers -eq 't') {
            if ((Invoke-Sql 'SELECT COUNT(*) FROM users' $DbName) -eq '0') { $new = $true }
        } else { $new = $true }

        if ($new) { $status = 'OK NEW' } else { $status = 'OK EXISTING' }
    } finally {
        # ---- always restore the original security settings ----
        Copy-Item $backup $hba -Force
        Remove-Item $backup -Force -ErrorAction SilentlyContinue
        Restart-Service -Name $svc.Name -Force
        Wait-Ready
    }
    Write-Result $status
} catch {
    Write-Result ("ERROR " + $_.Exception.Message)
    exit 1
}

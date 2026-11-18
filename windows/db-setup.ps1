<#
  Creates (or repairs) the PostgreSQL user and database for Maintenance Manager without needing the
  postgres password. Must run as Administrator; install.ps1 starts it elevated.

  How: adds two lines at the top of pg_hba.conf that let ONLY the "postgres" user connect from this
  computer (127.0.0.1 / ::1) without a password, runs the SQL, then ALWAYS restores the original file
  and restarts PostgreSQL. If an earlier run was interrupted (power cut), the saved original is put back
  first, so the temporary rule can never stay behind.

  -RequestFile: text file with 4 lines: service name, database name, database user, database password
                (a file, not command-line arguments, so the password isn't visible in the process list)
  Writes "OK NEW", "OK EXISTING" or "ERROR <message>" to -ResultFile.
#>
param(
    [Parameter(Mandatory = $true)][string]$RequestFile,
    [Parameter(Mandatory = $true)][string]$ResultFile
)
$ErrorActionPreference = 'Stop'

function Write-Result($text) { Set-Content -Path $ResultFile -Value $text -Encoding UTF8 }
function Quote-Sql($s) { return "'" + $s.Replace("'", "''") + "'" }

try {
    $req = Get-Content -Path $RequestFile -Encoding UTF8
    Remove-Item $RequestFile -Force -ErrorAction SilentlyContinue
    $serviceName, $dbName, $dbUser, $dbPassword = $req[0], $req[1], $req[2], $req[3]
    foreach ($id in @($dbName, $dbUser)) {
        if ($id -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') { throw "Invalid database or user name: $id" }
    }

    # ---- locate the service, its programs and its data folder ----
    $svc = Get-CimInstance Win32_Service -Filter "Name='$serviceName'"
    if (-not $svc) { throw "PostgreSQL service '$serviceName' was not found." }
    $image = $svc.PathName   # "C:\...\bin\pg_ctl.exe" runservice -N "postgresql-x64-16" -D "C:\...\data" -w
    if ($image -match '^"([^"]+)"') { $pgCtl = $Matches[1] } else { $pgCtl = ($image -split ' ')[0] }
    $bin = Split-Path $pgCtl
    if ($image -match '-D\s+"([^"]+)"') { $data = $Matches[1] }
    elseif ($image -match '-D\s+(\S+)') { $data = $Matches[1] }
    else { throw "Could not find the PostgreSQL data folder in: $image" }
    $psql = Join-Path $bin 'psql.exe'
    $isReady = Join-Path $bin 'pg_isready.exe'
    $hba = Join-Path $data 'pg_hba.conf'
    $backup = "$hba.mm-backup"
    foreach ($f in @($psql, $hba)) { if (-not (Test-Path $f)) { throw "Not found: $f" } }

    # an interrupted earlier run left the original behind: restore it before anything else
    if (Test-Path $backup) {
        Copy-Item $backup $hba -Force
        Remove-Item $backup -Force
    }

    function Wait-Ready {
        $ErrorActionPreference = 'Continue'   # PowerShell 5.1 treats native stderr as errors under 'Stop'
        for ($i = 0; $i -lt 60; $i++) {
            & $isReady -h 127.0.0.1 -q 2>$null
            if ($LASTEXITCODE -eq 0) { return }
            Start-Sleep -Seconds 1
        }
        throw 'PostgreSQL did not come back up after restarting.'
    }

    if ($svc.State -ne 'Running') { Start-Service -Name $serviceName }

    Copy-Item $hba $backup -Force
    try {
        # temporary rule: only the postgres superuser, only from this computer
        $original = Get-Content $hba -Raw
        $temp = "host all postgres 127.0.0.1/32 trust`r`nhost all postgres ::1/128 trust`r`n" + $original
        [System.IO.File]::WriteAllText($hba, $temp, (New-Object System.Text.UTF8Encoding($false)))
        Restart-Service -Name $serviceName -Force
        Wait-Ready

        function Invoke-Sql($sql, $db = 'postgres') {
            $ErrorActionPreference = 'Continue'
            # SQL goes in on stdin so the password never appears on a command line
            $out = $sql | & $psql -h 127.0.0.1 -U postgres -d $db -v ON_ERROR_STOP=1 -tA 2>&1
            if ($LASTEXITCODE -ne 0) { throw "Database command failed: $out" }
            return ($out | Out-String).Trim()
        }

        $pw = Quote-Sql $dbPassword
        if ((Invoke-Sql "SELECT 1 FROM pg_roles WHERE rolname = '$dbUser';") -eq '1') {
            # keep the database password in step with the app's settings
            Invoke-Sql "ALTER ROLE `"$dbUser`" WITH LOGIN PASSWORD $pw;" | Out-Null
        } else {
            Invoke-Sql "CREATE ROLE `"$dbUser`" LOGIN PASSWORD $pw;" | Out-Null
        }
        if ((Invoke-Sql "SELECT 1 FROM pg_database WHERE datname = '$dbName';") -ne '1') {
            Invoke-Sql "CREATE DATABASE `"$dbName`" OWNER `"$dbUser`";" | Out-Null
        }
        # "new" = the app hasn't created its users yet (it creates 'admin' on first start)
        $new = $true
        if ((Invoke-Sql "SELECT to_regclass('public.users') IS NOT NULL;" $dbName) -eq 't') {
            if ((Invoke-Sql 'SELECT EXISTS (SELECT 1 FROM users);' $dbName) -eq 't') { $new = $false }
        }
        if ($new) { $status = 'OK NEW' } else { $status = 'OK EXISTING' }
    } finally {
        # ---- always put the original security settings back ----
        Copy-Item $backup $hba -Force
        Remove-Item $backup -Force -ErrorAction SilentlyContinue
        Restart-Service -Name $serviceName -Force
        Wait-Ready
    }
    Write-Result $status
} catch {
    Write-Result ("ERROR " + $_.Exception.Message)
    exit 1
}

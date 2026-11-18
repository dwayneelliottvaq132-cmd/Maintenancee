<#
  Nightly backup (run by the "Maintenance Manager Backup" scheduled task):
  database dump + uploaded documents + reports into %LOCALAPPDATA%\MaintenanceManager\backups,
  keeping the last 30 days.
  Restore a dump with:  pg_restore -h localhost -U cmms -d cmms --clean --if-exists <file>.dump
#>
$ErrorActionPreference = 'Stop'
$AppHome = $PSScriptRoot
. (Join-Path $AppHome 'config.ps1')
$dir = Join-Path $AppHome 'backups'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$log = Join-Path $AppHome 'logs\backup.log'
try {
    if (-not $PgBin -or -not (Test-Path (Join-Path $PgBin 'pg_dump.exe'))) { throw "pg_dump.exe not found (PgBin = '$PgBin')" }
    if ($env:CMMS_DB_URL -notmatch 'jdbc:postgresql://([^:/]+)(?::(\d+))?/([^?]+)') { throw "Unexpected CMMS_DB_URL: $env:CMMS_DB_URL" }
    $dbHost = $Matches[1]; $port = if ($Matches[2]) { $Matches[2] } else { '5432' }; $db = $Matches[3]
    $env:PGPASSWORD = $env:CMMS_DB_PASSWORD   # environment, not command line
    $ErrorActionPreference = 'Continue'
    & (Join-Path $PgBin 'pg_dump.exe') -h $dbHost -p $port -U $env:CMMS_DB_USER -Fc -f (Join-Path $dir "cmms-db-$stamp.dump") $db 2>&1 |
        Out-File -FilePath $log -Append -Encoding UTF8
    if ($LASTEXITCODE -ne 0) { throw "pg_dump failed (exit $LASTEXITCODE)" }
    $ErrorActionPreference = 'Stop'
    $files = @($env:CMMS_UPLOAD_DIR, $env:CMMS_REPORT_DIR) | Where-Object { $_ -and (Test-Path $_) }
    if ($files) { Compress-Archive -Path $files -DestinationPath (Join-Path $dir "cmms-files-$stamp.zip") -Force }
    Get-ChildItem $dir -File | Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-30) } | Remove-Item -Force
    "$(Get-Date -Format s) backup OK $stamp" | Out-File -FilePath $log -Append -Encoding UTF8
} catch {
    "$(Get-Date -Format s) backup FAILED: $($_.Exception.Message)" | Out-File -FilePath $log -Append -Encoding UTF8
    exit 1
} finally {
    Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue
}

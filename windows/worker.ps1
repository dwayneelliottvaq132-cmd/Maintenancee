<#
  Runs a Python background job with the app's settings (used by the scheduled tasks):
    worker.ps1 alerts    - email alerts (every 15 minutes)
    worker.ps1 report    - monthly spending PDF/Excel (1st of the month)
#>
param([Parameter(Mandatory = $true)][ValidateSet('alerts', 'report', 'generate-pm')][string]$Job)
$ErrorActionPreference = 'Stop'
$AppHome = $PSScriptRoot
. (Join-Path $AppHome 'config.ps1')
$python = Join-Path $AppHome 'worker\.venv\Scripts\python.exe'
$log = Join-Path $AppHome "logs\worker-$Job.log"
if (-not (Test-Path $python)) { "$(Get-Date -Format s) Python worker is not installed" | Out-File $log -Append -Encoding UTF8; exit 1 }
Set-Location (Join-Path $AppHome 'worker')
$ErrorActionPreference = 'Continue'
& $python -m cmms_worker $Job 2>&1 | Out-File -FilePath $log -Append -Encoding UTF8
exit $LASTEXITCODE

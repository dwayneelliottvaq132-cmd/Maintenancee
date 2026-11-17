<#
  Stops the Maintenance Manager server running in the background.
  -Quiet : no message box.
#>
param([switch]$Quiet)
Add-Type -AssemblyName System.Windows.Forms
$procs = Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" |
    Where-Object { $_.CommandLine -like '*cmms-api.jar*' }
foreach ($p in $procs) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }
if (-not $Quiet) {
    $msg = if ($procs) { 'Maintenance Manager has been stopped.' } else { 'Maintenance Manager was not running.' }
    [System.Windows.Forms.MessageBox]::Show($msg, 'Maintenance Manager', 'OK', 'Information') | Out-Null
}

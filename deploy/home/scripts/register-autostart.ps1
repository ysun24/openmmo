<#
.SYNOPSIS
  Registers (or with -Remove, deletes) a Task Scheduler task that runs start.ps1 when you log
  on, after Docker Desktop is up. Docker Desktop itself must be set to start at login
  (Docker Desktop -> Settings -> General -> "Start Docker Desktop when you sign in").
#>
param([switch]$Remove)
$name = 'OpenMMO server'
if ($Remove) {
  Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue
  Write-Host "removed task '$name'"
  return
}
$start = Join-Path $PSScriptRoot 'start.ps1'
$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
  -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$start`" -WaitForDocker"
$trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
# Docker Desktop needs a minute after logon.
$trigger.Delay = 'PT1M'
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
  -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 30)
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Settings $settings `
  -Principal $principal -Description 'Starts the OpenMMO containers after Docker Desktop is up' -Force | Out-Null
Write-Host "registered task '$name': runs $start at logon"

<#
.SYNOPSIS
  Shows the containers, their health, the public URL and the image version.
#>
. (Join-Path $PSScriptRoot 'common.ps1')
Assert-Docker
Write-Step "OpenMMO $(Get-EnvValue 'OPENMMO_VERSION')"
[void](Invoke-Compose ps --all --format 'table {{.Service}}\t{{.State}}\t{{.Status}}')
Write-Host ''
Write-Host "On this PC : http://127.0.0.1:$(Get-EnvValue 'LOCAL_PORT')/"
if ((Get-EnvValue 'TUNNEL') -eq 'off') {
  Write-Host 'Public URL : tunnel is off (TUNNEL=off in .env)'
} else {
  $url = Get-TunnelUrl
  if ($url) { Write-Host "Public URL : $url" } else { Write-Host 'Public URL : not known yet (.\logs.ps1 tunnel)' }
}

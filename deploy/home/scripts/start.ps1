<#
.SYNOPSIS
  Starts the OpenMMO server (all containers). -WaitForDocker waits for Docker Desktop first,
  which is what the autostart task uses at logon.
#>
param([switch]$WaitForDocker)
. (Join-Path $PSScriptRoot 'common.ps1')
if ($WaitForDocker) { Wait-Docker 900 }
Assert-Docker
Write-Step 'Starting OpenMMO'
if ((Invoke-Compose up -d --remove-orphans) -ne 0) { throw 'docker compose up failed' }
if (Wait-Healthy 'gateway' 300) {
  Write-Ok "running. On this PC: http://127.0.0.1:$(Get-EnvValue 'LOCAL_PORT')/"
  if ((Get-EnvValue 'TUNNEL') -ne 'off') {
    $url = $null
    for ($i = 0; $i -lt 20 -and -not $url; $i++) { Start-Sleep -Seconds 3; $url = Get-TunnelUrl }
    if ($url) { Write-Ok "public URL: $url" } else { Write-Warn2 'no public URL yet: .\logs.ps1 tunnel' }
  }
} else {
  Write-Warn2 'the gateway is not healthy yet: .\status.ps1 and .\logs.ps1'
}

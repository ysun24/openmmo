<#
.SYNOPSIS
  Stops every OpenMMO container. Data stays in the Docker volumes; start.ps1 brings it back.
#>
. (Join-Path $PSScriptRoot 'common.ps1')
Assert-Docker
Write-Step 'Stopping OpenMMO (the game server saves every character first)'
if ((Invoke-Compose stop) -ne 0) { throw 'docker compose stop failed' }
Write-Ok 'stopped'

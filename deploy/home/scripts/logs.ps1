<#
.SYNOPSIS
  Shows container logs. .\logs.ps1 -> all services, last 100 lines each.
  .\logs.ps1 gateway -Follow -> follow one service (login, game, gateway, tunnel, login-db, game-db).
#>
param(
  [ValidateSet('', 'login', 'game', 'gateway', 'tunnel', 'login-db', 'game-db', 'keys')]
  [string]$Service = '',
  [int]$Tail = 100,
  [switch]$Follow
)
. (Join-Path $PSScriptRoot 'common.ps1')
Assert-Docker
$a = @('logs', '--no-color', '--timestamps', '--tail', "$Tail")
if ($Follow) { $a += '--follow' }
if ($Service) { $a += $Service }
[void](Invoke-Compose @a)

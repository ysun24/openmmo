<#
.SYNOPSIS
  Updates an installed server to the bundle this script is in. Run it from the NEW unzipped
  bundle folder. Takes a backup, loads the new image, refreshes compose.yml and the scripts,
  keeps .env (new settings are appended) and every Docker volume, then recreates the
  containers. Database migrations run by themselves when login and game start.
#>
param([string]$InstallDir = (Join-Path $env:USERPROFILE 'OpenMMO'))
$bundle = $PSScriptRoot
. (Join-Path $bundle 'common.ps1')
Assert-Docker
$InstallDir = [System.IO.Path]::GetFullPath($InstallDir)
if (-not (Test-Path (Join-Path $InstallDir '.env'))) { throw "no installation in $InstallDir (run setup.ps1 for a new install)" }
$version = (Get-Content -LiteralPath (Join-Path $bundle 'VERSION') -TotalCount 1).Trim()
$imageTar = Join-Path $bundle "openmmo-image-$version.tar.gz"
if (-not (Test-Path $imageTar)) { throw "image file $imageTar is missing" }

. (Join-Path $InstallDir 'common.ps1')
$old = Get-EnvValue 'OPENMMO_VERSION'
Write-Step "Updating $old -> $version"
# A failing backup throws and stops the update before anything changed.
& (Join-Path $InstallDir 'backup.ps1')
# setup.ps1 does the rest idempotently: files, new settings, image, up -d.
& (Join-Path $bundle 'setup.ps1') -InstallDir $InstallDir
Write-Ok "now on $(Get-EnvValue 'OPENMMO_VERSION'). Old images can be removed with: docker image rm openmmo:$old"

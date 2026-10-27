<#
.SYNOPSIS
  Installs or repairs the OpenMMO home server. Safe to run again at any time.
.DESCRIPTION
  Run from the unzipped bundle folder:
    powershell -ExecutionPolicy Bypass -File .\setup.ps1
  1. copies the bundle to -InstallDir (default %USERPROFILE%\OpenMMO)
  2. creates .env from .env.example and generates every missing secret
     (database passwords, session secret, admin password, site password); existing values and
     data are never touched
  3. restricts .env to your Windows account
  4. docker load's the image and starts everything with docker compose
.PARAMETER InstallDir
  Where the server lives. Re-running setup.ps1 or update.ps1 with the same folder keeps all data.
.PARAMETER AskToken
  Ask for the Pinggy token again even when .env already has one.
#>
param(
  [string]$InstallDir = (Join-Path $env:USERPROFILE 'OpenMMO'),
  [switch]$AskToken,
  [switch]$NoStart
)
$bundle = $PSScriptRoot
. (Join-Path $bundle 'common.ps1')

Write-Step 'Checking Docker'
Assert-Docker
Write-Ok 'Docker is running'

$bundleVersion = (Get-Content -LiteralPath (Join-Path $bundle 'VERSION') -TotalCount 1).Trim()
$imageTar = Join-Path $bundle "openmmo-image-$bundleVersion.tar.gz"
if (-not (Test-Path $imageTar)) { throw "image file $imageTar is missing from the bundle" }

# --- 1. files ------------------------------------------------------------------------------------
$InstallDir = [System.IO.Path]::GetFullPath($InstallDir)
Write-Step "Installing to $InstallDir"
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
$same = ([System.IO.Path]::GetFullPath($bundle).TrimEnd('\') -ieq $InstallDir.TrimEnd('\'))
if (-not $same) {
  Get-ChildItem -LiteralPath $bundle -File -Force |
    Where-Object { $_.Name -ne '.env' -and $_.Name -notlike 'openmmo-image-*.tar.gz' } |
    ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $InstallDir -Force }
}
# Re-point the helpers at the install folder.
. (Join-Path $InstallDir 'common.ps1')

# --- 2. .env -------------------------------------------------------------------------------------
$fresh = -not (Test-Path $EnvFile)
if ($fresh) {
  Copy-Item -LiteralPath (Join-Path $InstallDir '.env.example') -Destination $EnvFile
  Write-Ok 'created .env from .env.example'
} else {
  # Settings added by a newer bundle are appended with their default value.
  foreach ($line in Get-Content -LiteralPath (Join-Path $InstallDir '.env.example') -Encoding UTF8) {
    if ($line -match '^([A-Z0-9_]+)=(.*)$' -and $null -eq (Get-EnvValue $Matches[1])) {
      [void](Set-EnvValue $Matches[1] $Matches[2])
      Write-Ok "added new setting $($Matches[1])"
    }
  }
}
Protect-Path $EnvFile

$generated = @()
foreach ($pair in @(@('LOGIN_DB_PASSWORD', 32), @('GAME_DB_PASSWORD', 32),
                    @('OPENMMO_SESSION_SECRET', 64), @('OPENMMO_ADMIN_PASSWORD', 20))) {
  if (Set-EnvValue $pair[0] (New-Secret $pair[1]) -OnlyIfEmpty) { $generated += $pair[0] }
}
# Site password only while basic auth is wanted (SITE_USER set) and none was chosen yet.
if ((Get-EnvValue 'SITE_USER') -and -not (Get-EnvValue 'SITE_PASS')) {
  [void](Set-EnvValue 'SITE_PASS' (New-Secret 16))
  $generated += 'SITE_PASS'
}
if ($generated.Count -gt 0) { Write-Ok ("generated: " + ($generated -join ', ')) }
else { Write-Ok 'all secrets already set, nothing changed' }

if ($AskToken -or ($fresh -and -not (Get-EnvValue 'PINGGY_TOKEN'))) {
  Write-Host ''
  Write-Host 'Pinggy token (dashboard.pinggy.io -> Tokens). Press Enter to skip and use the free tier.'
  $sec = Read-Host -AsSecureString 'Token'
  $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
  try { $tok = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
  if ($tok -and $tok.Trim()) {
    if ($tok.Trim() -notmatch '^[A-Za-z0-9._-]+$') { throw 'that does not look like a Pinggy token' }
    [void](Set-EnvValue 'PINGGY_TOKEN' $tok.Trim())
    Write-Ok 'token saved to .env'
    $sub = Read-Host 'Persistent domain bound to the token, e.g. mygame.a.pinggy.link (Enter = none)'
    if ($sub -and $sub.Trim()) {
      $sub = $sub.Trim() -replace '^https?://', '' -replace '/.*$', ''
      [void](Set-EnvValue 'PINGGY_SUBDOMAIN' $sub)
    }
  }
  $tok = $null
}

# --- 3. image ------------------------------------------------------------------------------------
Write-Step "Loading image openmmo:$bundleVersion (takes a minute)"
& docker image inspect "openmmo:$bundleVersion" *> $null
if ($LASTEXITCODE -eq 0) {
  Write-Ok 'image already loaded'
} else {
  & docker load -i $imageTar
  if ($LASTEXITCODE -ne 0) { throw 'docker load failed' }
}
[void](Set-EnvValue 'OPENMMO_VERSION' $bundleVersion)

if ($NoStart) { Write-Ok 'not starting (-NoStart)'; return }

# --- 4. start ------------------------------------------------------------------------------------
Write-Step 'Starting (first start creates the databases and the server keys)'
if ((Invoke-Compose up -d --remove-orphans) -ne 0) { throw 'docker compose up failed, see the output above (or logs.ps1)' }
if (-not (Wait-Healthy 'gateway' 300)) { throw 'the gateway did not become healthy; run status.ps1 and logs.ps1' }
Write-Ok "running. On this PC: http://127.0.0.1:$(Get-EnvValue 'LOCAL_PORT')/"
if ((Get-EnvValue 'TUNNEL') -ne 'off') {
  $url = $null
  for ($i = 0; $i -lt 20 -and -not $url; $i++) { Start-Sleep -Seconds 3; $url = Get-TunnelUrl }
  if ($url) { Write-Ok "public URL: $url" } else { Write-Warn2 'no public URL yet; run status.ps1 in a minute' }
}
Write-Host ''
Write-Host "Admin login : $(Get-EnvValue 'OPENMMO_ADMIN_USERNAME') / (OPENMMO_ADMIN_PASSWORD in $EnvFile)"
if (Get-EnvValue 'SITE_USER') {
  Write-Host "Site login  : $(Get-EnvValue 'SITE_USER') / (SITE_PASS in $EnvFile) - give this to your friends"
}
Write-Host "Invite codes: $InstallDir\invite.ps1 -Uses 1 -Days 7"

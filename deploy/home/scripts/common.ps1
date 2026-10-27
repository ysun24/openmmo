# Shared helpers for the OpenMMO home scripts. Dot-sourced: . "$PSScriptRoot\common.ps1"
# ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less scripts in the ANSI code page.
Set-StrictMode -Version 3.0
# 'Continue', not 'Stop': in Windows PowerShell 5.1 a native command writing to a redirected
# stderr would otherwise abort the script. Every native call checks $LASTEXITCODE instead.
$ErrorActionPreference = 'Continue'

$script:Here = $PSScriptRoot
$script:EnvFile = Join-Path $Here '.env'
$script:ComposeFile = Join-Path $Here 'compose.yml'
# Compose project name; the volumes are <project>_login_db etc. Override only for testing.
$script:Project = if ($env:OPENMMO_PROJECT) { $env:OPENMMO_PROJECT } else { 'openmmo' }

function Write-Step([string]$msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg) { Write-Host "    $msg" -ForegroundColor Green }
function Write-Warn2([string]$msg) { Write-Host "    $msg" -ForegroundColor Yellow }

function Assert-Docker {
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'docker was not found. Install Docker Desktop (WSL2 backend) first, see README.md.' }
  & docker info --format '{{.ServerVersion}}' *> $null
  if ($LASTEXITCODE -ne 0) { throw 'Docker is not running. Start Docker Desktop and wait until it says "Engine running".' }
  & docker compose version *> $null
  if ($LASTEXITCODE -ne 0) { throw '"docker compose" is missing. Update Docker Desktop.' }
}

function Wait-Docker([int]$Seconds = 600) {
  $deadline = (Get-Date).AddSeconds($Seconds)
  while ((Get-Date) -lt $deadline) {
    & docker info --format '{{.ServerVersion}}' *> $null
    if ($LASTEXITCODE -eq 0) { return }
    Start-Sleep -Seconds 5
  }
  throw "Docker did not come up within $Seconds seconds."
}

# Runs docker compose for this install. Returns the exit code; output goes to the console.
# Deliberately a simple function using $args: with a param() block PowerShell would treat
# compose's own flags as its common parameters (-d would become -Debug).
function Invoke-Compose {
  $ComposeArgs = @($args)
  if (-not (Test-Path $EnvFile)) { throw ".env not found in $Here - run setup.ps1 first." }
  & docker compose --project-name $Project --env-file $EnvFile -f $ComposeFile @ComposeArgs | Out-Host
  return $LASTEXITCODE
}

# --- .env -----------------------------------------------------------------------------------------
function Read-EnvLines { if (Test-Path $EnvFile) { return @(Get-Content -LiteralPath $EnvFile -Encoding UTF8) } else { return @() } }

function Get-EnvValue([string]$Key) {
  foreach ($line in Read-EnvLines) {
    if ($line -match "^\s*$([regex]::Escape($Key))=(.*)$") { return $Matches[1].Trim() }
  }
  return $null
}

function Save-EnvLines([string[]]$Lines) {
  $enc = New-Object System.Text.UTF8Encoding($false)
  [System.IO.File]::WriteAllLines($EnvFile, $Lines, $enc)
}

# Sets KEY=VALUE. With -OnlyIfEmpty an existing non-empty value is left alone (secrets).
function Set-EnvValue([string]$Key, [string]$Value, [switch]$OnlyIfEmpty) {
  $lines = [System.Collections.Generic.List[string]]::new()
  foreach ($l in Read-EnvLines) { $lines.Add($l) }
  $pattern = "^\s*$([regex]::Escape($Key))=(.*)$"
  for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match $pattern) {
      if ($OnlyIfEmpty -and $Matches[1].Trim() -ne '') { return $false }
      $lines[$i] = "$Key=$Value"
      Save-EnvLines $lines.ToArray()
      return $true
    }
  }
  $lines.Add("$Key=$Value")
  Save-EnvLines $lines.ToArray()
  return $true
}

# Letters and digits only, so the values are safe in .env, URLs and Pinggy's user string.
function New-Secret([int]$Length = 32) {
  $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  $sb = New-Object System.Text.StringBuilder
  $buf = New-Object byte[] 1
  $limit = 256 - (256 % $alphabet.Length)
  while ($sb.Length -lt $Length) {
    $rng.GetBytes($buf)
    if ($buf[0] -lt $limit) { [void]$sb.Append($alphabet[$buf[0] % $alphabet.Length]) }
  }
  $rng.Dispose()
  return $sb.ToString()
}

# Only the current user (and SYSTEM / Administrators) may read a secrets file or folder.
function Protect-Path([string]$Path) {
  $who = "$env:USERDOMAIN\$env:USERNAME"
  if (-not (Get-Command icacls -ErrorAction SilentlyContinue)) { Write-Warn2 "icacls not found; $Path keeps its default permissions"; return }
  & icacls $Path /inheritance:r /grant:r "${who}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' *> $null
  if ($LASTEXITCODE -ne 0) {
    # Files do not take (OI)(CI).
    & icacls $Path /inheritance:r /grant:r "${who}:F" '*S-1-5-18:F' '*S-1-5-32-544:F' *> $null
  }
  if ($LASTEXITCODE -ne 0) { Write-Warn2 "could not restrict access to $Path (icacls exit $LASTEXITCODE)" }
}

# The newest public URL the tunnel printed, or $null.
function Get-TunnelUrl {
  $sub = Get-EnvValue 'PINGGY_SUBDOMAIN'
  if ($sub) { return "https://$sub/" }
  $out = & docker compose --project-name $Project --env-file $EnvFile -f $ComposeFile logs --no-color --tail 200 tunnel 2>$null
  $url = $null
  foreach ($l in @($out)) { if ($l -match 'PUBLIC URL: (https://\S+)') { $url = $Matches[1] } }
  return $url
}

function Wait-Healthy([string]$Service, [int]$Seconds = 240) {
  $deadline = (Get-Date).AddSeconds($Seconds)
  while ((Get-Date) -lt $deadline) {
    $id = & docker compose --project-name $Project --env-file $EnvFile -f $ComposeFile ps -q $Service 2>$null
    if ($id) {
      $state = & docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $id 2>$null
      if ($state -eq 'healthy') { return $true }
    }
    Start-Sleep -Seconds 3
  }
  return $false
}

<#
.SYNOPSIS
  Restores both databases from a backup folder made by backup.ps1. Everything since that backup
  is lost, so a fresh safety backup is taken first.
.PARAMETER From
  The backup folder, e.g. .\backups\2026-10-27_031500. Default: the newest one.
.PARAMETER WithKeys
  Also put the backup's server keys back (only needed after the keys volume was lost).
#>
param([string]$From, [switch]$WithKeys, [switch]$Force)
. (Join-Path $PSScriptRoot 'common.ps1')
Assert-Docker
if (-not $From) {
  $From = @(Get-ChildItem -LiteralPath (Join-Path $Here 'backups') -Directory -ErrorAction SilentlyContinue |
      Sort-Object Name -Descending | Select-Object -First 1 | ForEach-Object { $_.FullName })[0]
  if (-not $From) { throw 'no backups found; pass -From <folder>' }
}
$From = (Resolve-Path -LiteralPath $From).Path
foreach ($f in 'login.dump', 'game.dump') {
  if (-not (Test-Path (Join-Path $From $f))) { throw "$From has no $f" }
}
if (-not $Force) {
  $answer = Read-Host "Replace ALL game and account data with the backup in $From ? Type yes"
  if ($answer -ne 'yes') { Write-Warn2 'cancelled'; return }
}

Write-Step 'Safety backup of the current data'
& (Join-Path $Here 'backup.ps1') -Keep 1000
Write-Step 'Stopping login, game, gateway and tunnel'
[void](Invoke-Compose stop tunnel gateway game login)
[void](Invoke-Compose up -d login-db game-db)
if (-not (Wait-Healthy 'login-db' 120) -or -not (Wait-Healthy 'game-db' 120)) { throw 'databases did not come up' }

foreach ($db in @(
    @{ Svc = 'login-db'; Name = (Get-EnvValue 'LOGIN_DB_NAME'); User = (Get-EnvValue 'LOGIN_DB_USER'); File = 'login.dump' },
    @{ Svc = 'game-db'; Name = (Get-EnvValue 'GAME_DB_NAME'); User = (Get-EnvValue 'GAME_DB_USER'); File = 'game.dump' })) {
  Write-Step "Restoring $($db.Svc)"
  $id = ("" + (& docker compose --project-name $Project --env-file $EnvFile -f $ComposeFile ps -q $db.Svc)).Trim()
  & docker cp (Join-Path $From $db.File) "${id}:/tmp/$($db.File)"
  if ($LASTEXITCODE -ne 0) { throw "copying $($db.File) failed" }
  & docker exec $id pg_restore -U $db.User -d $db.Name --clean --if-exists --no-owner --single-transaction "/tmp/$($db.File)"
  if ($LASTEXITCODE -ne 0) { throw "pg_restore of $($db.Svc) failed; the safety backup is in backups\" }
  & docker exec $id rm -f "/tmp/$($db.File)" | Out-Null
  Write-Ok 'done'
}

if ($WithKeys) {
  Write-Step 'Restoring server keys'
  # The finished one-shot 'keys' container still has the keys volume mounted read-write.
  [void](Invoke-Compose up --no-start keys)
  $kid = ("" + (& docker compose --project-name $Project --env-file $EnvFile -f $ComposeFile ps -aq keys)).Trim()
  foreach ($k in 'game.private.pem', 'game.public.pem') {
    & docker cp (Join-Path $From "keys\$k") "${kid}:/keys/$k"
    if ($LASTEXITCODE -ne 0) { throw "copying $k failed" }
  }
  Write-Ok 'keys restored'
}

Write-Step 'Starting everything again'
& (Join-Path $Here 'start.ps1')

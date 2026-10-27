<#
.SYNOPSIS
  Backs up both databases, the server keys and .env into backups\<date-time>\, keeping the
  newest 14 backups. Runs while the server is up (pg_dump takes a consistent snapshot).
.PARAMETER Keep
  How many backup folders to keep (default 14).
#>
param([ValidateRange(1, 1000)][int]$Keep = 14, [string]$BackupRoot)
. (Join-Path $PSScriptRoot 'common.ps1')
Assert-Docker
if (-not $BackupRoot) { $BackupRoot = Join-Path $Here 'backups' }
$stamp = Get-Date -Format 'yyyy-MM-dd_HHmmss'
$dest = Join-Path $BackupRoot $stamp
New-Item -ItemType Directory -Force -Path $dest | Out-Null
Protect-Path $BackupRoot

function Get-ContainerId([string]$svc) {
  $id = & docker compose --project-name $Project --env-file $EnvFile -f $ComposeFile ps -q $svc 2>$null
  if (-not $id) { throw "$svc is not running - start the server first (start.ps1)" }
  return "$id".Trim()
}

foreach ($db in @(
    @{ Svc = 'login-db'; Name = (Get-EnvValue 'LOGIN_DB_NAME'); User = (Get-EnvValue 'LOGIN_DB_USER'); File = 'login.dump' },
    @{ Svc = 'game-db'; Name = (Get-EnvValue 'GAME_DB_NAME'); User = (Get-EnvValue 'GAME_DB_USER'); File = 'game.dump' })) {
  Write-Step "Dumping $($db.Svc)"
  $id = Get-ContainerId $db.Svc
  # Dump to a file inside the container and copy it out: PowerShell pipes would mangle binary data.
  & docker exec $id pg_dump -U $db.User -d $db.Name -Fc -f "/tmp/$($db.File)"
  if ($LASTEXITCODE -ne 0) { throw "pg_dump of $($db.Svc) failed" }
  & docker cp "${id}:/tmp/$($db.File)" (Join-Path $dest $db.File)
  if ($LASTEXITCODE -ne 0) { throw "copying the $($db.Svc) dump failed" }
  & docker exec $id rm -f "/tmp/$($db.File)" | Out-Null
  Write-Ok ("{0} ({1:N0} KB)" -f $db.File, ((Get-Item (Join-Path $dest $db.File)).Length / 1KB))
}

Write-Step 'Copying server keys and .env'
$login = Get-ContainerId 'login'
New-Item -ItemType Directory -Force -Path (Join-Path $dest 'keys') | Out-Null
& docker cp "${login}:/keys/game.private.pem" (Join-Path $dest 'keys\game.private.pem')
if ($LASTEXITCODE -ne 0) { throw 'copying the private key failed' }
& docker cp "${login}:/keys/game.public.pem" (Join-Path $dest 'keys\game.public.pem')
Copy-Item -LiteralPath $EnvFile -Destination (Join-Path $dest 'env.backup')
Get-Content -LiteralPath (Join-Path $Here 'VERSION') -ErrorAction SilentlyContinue | Set-Content (Join-Path $dest 'VERSION')
Write-Ok "backup in $dest"

$all = @(Get-ChildItem -LiteralPath $BackupRoot -Directory | Where-Object { $_.Name -match '^\d{4}-\d{2}-\d{2}_\d{6}$' } | Sort-Object Name -Descending)
if ($all.Count -gt $Keep) {
  foreach ($old in $all[$Keep..($all.Count - 1)]) {
    Remove-Item -LiteralPath $old.FullName -Recurse -Force
    Write-Ok "removed old backup $($old.Name)"
  }
}

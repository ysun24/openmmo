<#
.SYNOPSIS
  Creates invite codes for friends, or lists / revokes them.
.EXAMPLE
  .\invite.ps1                 one code, one use, valid 7 days
  .\invite.ps1 -Uses 5 -Days 30 -Count 2
  .\invite.ps1 -List
  .\invite.ps1 -Revoke ABCD-EFGH-JKLM
#>
param(
  [ValidateRange(1, 1000)][int]$Uses = 1,
  [ValidateRange(0, 3650)][int]$Days = 7,
  [ValidateRange(1, 100)][int]$Count = 1,
  [switch]$List,
  [ValidatePattern('^[A-Za-z0-9-]{4,32}$')][string]$Revoke
)
. (Join-Path $PSScriptRoot 'common.ps1')
Assert-Docker
if ($List) { $cli = @('list') }
elseif ($Revoke) { $cli = @('revoke', $Revoke) }
else { $cli = @('create', '--uses', "$Uses", '--days', "$Days", '--count', "$Count", '--by', 'invite.ps1') }

# A throwaway container of the login service runs the CLI against login-db.
$out = & docker compose --project-name $Project --env-file $EnvFile -f $ComposeFile `
  run --rm --no-deps -T login invite @cli 2>&1
$rc = $LASTEXITCODE
$url = Get-TunnelUrl
foreach ($line in @($out)) {
  $s = "$line"
  # Skip the JVM / logback chatter, keep what the CLI printed.
  if ($s -match '^\d\d:\d\d:\d\d\.\d{3} \[' -or $s -match '^(Picked up JAVA_TOOL_OPTIONS|kotlin-logging|jOOQ tip|\s*@@)' -or $s -match '^\s*$') { continue }
  Write-Host $s
  if (-not $List -and -not $Revoke -and $url -and $s -match '^[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$') {
    Write-Host ("    link: " + $url.TrimEnd('/') + "/#invite=$s") -ForegroundColor Green
  }
}
if ($rc -ne 0) { throw "invite command failed (exit $rc)" }

$ErrorActionPreference = "Stop"
$runDir = Join-Path $PSScriptRoot ".run"
$pidFile = Join-Path $runDir "multi-ev-pids.json"

if (-not (Test-Path $pidFile)) {
  Write-Host "No PID map found at $pidFile"
  exit 0
}

$records = Get-Content -Raw -Path $pidFile | ConvertFrom-Json
if ($records -isnot [System.Array]) {
  $records = @($records)
}

foreach ($r in $records) {
  $proc = Get-Process -Id $r.pid -ErrorAction SilentlyContinue
  if ($proc) {
    Stop-Process -Id $r.pid -Force -ErrorAction SilentlyContinue
    Write-Host "Stopped $($r.stationId) (pid=$($r.pid))"
  } else {
    Write-Host "Already stopped $($r.stationId) (pid=$($r.pid))"
  }
}

Remove-Item -Path $pidFile -Force -ErrorAction SilentlyContinue
Write-Host "Cleanup complete."

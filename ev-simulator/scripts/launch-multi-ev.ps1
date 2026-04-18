param(
  [int]$Count = 3,
  [int]$BasePort = 8080,
  [string]$StationPrefix = "CS-10",
  [string]$GatewayUriTemplate = "ws://localhost:8443/ocpp/{stationId}"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$simApp = Join-Path $root "simulator-app"
$runDir = Join-Path $PSScriptRoot ".run"
$pidFile = Join-Path $runDir "multi-ev-pids.json"

if (-not (Test-Path $simApp)) {
  throw "Simulator app folder not found: $simApp"
}

if ($Count -lt 1) {
  throw "Count must be >= 1"
}

if (-not (Test-Path $runDir)) {
  New-Item -Path $runDir -ItemType Directory | Out-Null
}

$records = @()

for ($i = 1; $i -le $Count; $i++) {
  $stationId = "$StationPrefix$i-EV"
  $port = $BasePort + $i - 1
  $stdoutLog = Join-Path $runDir "sim-$stationId.out.log"
  $stderrLog = Join-Path $runDir "sim-$stationId.err.log"

  $mvnArgs = @(
    "spring-boot:run",
    "-Dspring-boot.run.arguments=--server.port=$port --ev.simulator.station-id=$stationId --ev.simulator.gateway-uri=$GatewayUriTemplate --ev.simulator.tls.enabled=false --ev.simulator.auto-scenarios=false"
  )

  $process = Start-Process -FilePath "mvn" `
    -ArgumentList $mvnArgs `
    -WorkingDirectory $simApp `
    -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog `
    -PassThru

  $records += [pscustomobject]@{
    stationId = $stationId
    apiPort = $port
    pid = $process.Id
    stdoutLog = $stdoutLog
    stderrLog = $stderrLog
    startedAt = (Get-Date).ToString("o")
  }

  Write-Host "Started $stationId on api port $port (pid=$($process.Id))"
}

$records | ConvertTo-Json -Depth 5 | Set-Content -Path $pidFile -Encoding UTF8
Write-Host "Saved process map: $pidFile"
Write-Host "Use .\\stop-multi-ev.ps1 to stop all launched instances."

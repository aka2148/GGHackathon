param(
    [switch]$Bootstrap
)

$ErrorActionPreference = 'Stop'

$localEnvPath = Join-Path $PSScriptRoot 'local-env.ps1'
if (Test-Path $localEnvPath) {
    . $localEnvPath
} else {
    Write-Warning "Missing scripts/local-env.ps1. Copy scripts/local-env.ps1.example and fill in your local values."
}

if ($Bootstrap) {
    $env:GG_BOOTSTRAP_ENABLED = 'true'
}

mvn spring-boot:run

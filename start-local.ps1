param(
    [switch]$Bootstrap
)

$ErrorActionPreference = 'Stop'

$scriptRunner = Join-Path $PSScriptRoot 'scripts\start-local.ps1'
if (-not (Test-Path $scriptRunner)) {
    throw "Missing scripts/start-local.ps1"
}

if ($Bootstrap) {
    & $scriptRunner -Bootstrap
} else {
    & $scriptRunner
}

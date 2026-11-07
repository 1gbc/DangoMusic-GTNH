$ErrorActionPreference = 'Stop'
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Host 'Please install Node.js 22+ from https://nodejs.org/ first.'
    Read-Host 'Press Enter to close'
    exit 1
}
& node (Join-Path $PSScriptRoot 'bridge.cjs')

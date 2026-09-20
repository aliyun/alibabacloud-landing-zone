# Arguments are forwarded unchanged to the shared, read-only Python core.
$ErrorActionPreference = 'Stop'
$python = if ($env:AUTOWONDER_PYTHON) { $env:AUTOWONDER_PYTHON } else { & (Join-Path $PSScriptRoot 'runtime-bootstrap.ps1') }
if (-not $python) { throw 'Private Python bootstrap failed' }
& $python (Join-Path (Split-Path $PSScriptRoot -Parent) 'public_ip.py') @args
exit $LASTEXITCODE

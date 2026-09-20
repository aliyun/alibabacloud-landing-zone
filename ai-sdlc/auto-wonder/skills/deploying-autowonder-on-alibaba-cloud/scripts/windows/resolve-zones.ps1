# Arguments are forwarded to the shared, read-only Python core. The documented
# entrypoint is `resolve-zones.ps1 --manifest FILE`; default to the resolve
# subcommand so that call works. `validate` and help stay explicit.
$ErrorActionPreference = 'Stop'
$python = if ($env:AUTOWONDER_PYTHON) { $env:AUTOWONDER_PYTHON } else { & (Join-Path $PSScriptRoot 'runtime-bootstrap.ps1') }
if (-not $python) { throw 'Private Python bootstrap failed' }
$forward = @($args)
if ($forward.Count -eq 0 -or $forward[0] -notin @('resolve','validate','discover','check-plan','check-binding','-h','--help')) {
  $forward = @('resolve') + $forward
}
& $python (Join-Path (Split-Path $PSScriptRoot -Parent) 'resolve_zones.py') @forward
exit $LASTEXITCODE

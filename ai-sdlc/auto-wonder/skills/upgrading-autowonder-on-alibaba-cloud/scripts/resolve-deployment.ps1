[CmdletBinding()]
param(
    [string]$Manifest,
    [string]$SearchRoot = (Get-Location).Path,
    [string]$DeploymentDirectory,
    [string]$Region,
    [string]$DeploymentId
)

$ErrorActionPreference = 'Stop'
$core = Join-Path $PSScriptRoot 'operations-store.py'
$arguments = @($core, 'resolve', '--project-root', $SearchRoot)
if ($Manifest) { $arguments += @('--manifest', $Manifest) }
if ($DeploymentDirectory) { $arguments += @('--deployment-dir', $DeploymentDirectory) }
if ($Region) { $arguments += @('--region', $Region) }
if ($DeploymentId) { $arguments += @('--deployment-id', $DeploymentId) }
$output = (& python @arguments) -join [Environment]::NewLine
if ($LASTEXITCODE -ne 0) {
    if ($output) { $output }
    exit $LASTEXITCODE
}

. (Join-Path $PSScriptRoot 'windows\lib.ps1')
$result = $output | ConvertFrom-Json
$infoDirectory = Split-Path -Parent ([string]$result.manifest)
Get-ChildItem -LiteralPath $infoDirectory -Filter '*.json' -File |
    ForEach-Object { Protect-CurrentUserFile -Path $_.FullName }
$output

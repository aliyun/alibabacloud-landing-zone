[CmdletBinding()]
param(
    [string]$Manifest,
    [string]$SearchRoot = (Get-Location).Path,
    [string]$DeploymentDirectory
)

$ErrorActionPreference = 'Stop'
$core = Join-Path $PSScriptRoot 'upgrade_info.py'
$arguments = @($core, 'locate', '--project-root', $SearchRoot)
if ($Manifest) { $arguments += @('--manifest', $Manifest) }
if ($DeploymentDirectory) { $arguments += @('--deployment-dir', $DeploymentDirectory) }
$output = (& python @arguments) -join [Environment]::NewLine
if ($LASTEXITCODE -ne 0) {
    if ($output) { $output }
    exit $LASTEXITCODE
}

$DeploySkill = Join-Path $PSScriptRoot '..\..\deploying-autowonder-on-alibaba-cloud'
. (Join-Path $DeploySkill 'scripts\windows\lib.ps1')
$result = $output | ConvertFrom-Json
$infoDirectory = Split-Path -Parent ([string]$result.manifest)
Get-ChildItem -LiteralPath $infoDirectory -Filter '*.json' -File |
    ForEach-Object { Protect-CurrentUserFile -Path $_.FullName }
$output

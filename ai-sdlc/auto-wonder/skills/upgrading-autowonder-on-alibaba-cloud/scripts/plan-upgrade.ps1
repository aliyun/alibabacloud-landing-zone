[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Manifest,
    [Parameter(Mandatory)][string]$SourceDirectory,
    [Parameter(Mandatory)][string]$EnvFile,
    [string]$BaselineDirectory,
    [switch]$WorkspaceCurrentContent,
    [switch]$ForceRedeploy
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'windows\lib.ps1')
Protect-CurrentUserFile -Path $Manifest
Protect-CurrentUserFile -Path $EnvFile
$data = Get-ManifestData -Manifest $Manifest
Assert-VerifiedUpgradeTargets -ManifestData $data
$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) { $python = Get-Command python3 -ErrorAction Stop }
$arguments = @('-B', (Join-Path $PSScriptRoot 'upgrade_plan.py'), 'plan', '--manifest', $Manifest,
    '--source-dir', $SourceDirectory, '--env-file', $EnvFile)
if ($ForceRedeploy) { $arguments += '--force-redeploy' }
if ($WorkspaceCurrentContent) { $arguments += '--workspace-current-content' }
if ($BaselineDirectory) { $arguments += @('--baseline-dir', $BaselineDirectory) }
& $python.Source @arguments
if ($LASTEXITCODE -ne 0) { throw 'Upgrade planning failed; no upgrade is authorized' }

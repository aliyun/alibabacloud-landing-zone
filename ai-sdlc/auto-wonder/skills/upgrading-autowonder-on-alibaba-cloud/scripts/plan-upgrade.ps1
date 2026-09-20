[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Manifest,
    [Parameter(Mandatory)][string]$SourceDirectory,
    [string]$EnvFile,
    [string]$TargetRef,
    [string]$RepositoryUrl,
    [switch]$AllowRepositoryChange,
    [string]$BaselineDirectory,
    [switch]$WorkspaceCurrentContent,
    [switch]$ForceRedeploy
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'windows\lib.ps1')
Protect-CurrentUserFile -Path $Manifest
if ($EnvFile) { Protect-CurrentUserFile -Path $EnvFile }
$data = Get-ManifestData -Manifest $Manifest
Assert-VerifiedUpgradeTargets -ManifestData $data
$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) { $python = Get-Command python3 -ErrorAction Stop }
$arguments = @('-B', (Join-Path $PSScriptRoot 'upgrade_plan.py'), 'plan', '--manifest', $Manifest,
    '--source-dir', $SourceDirectory)
if ($EnvFile) { $arguments += @('--env-file', $EnvFile) }
if ($TargetRef) { $arguments += @('--target-ref', $TargetRef) }
if ($RepositoryUrl) { $arguments += @('--repository-url', $RepositoryUrl) }
if ($AllowRepositoryChange) { $arguments += '--allow-repository-change' }
if ($ForceRedeploy) { $arguments += '--force-redeploy' }
if ($WorkspaceCurrentContent) { $arguments += '--workspace-current-content' }
if ($BaselineDirectory) { $arguments += @('--baseline-dir', $BaselineDirectory) }
& $python.Source @arguments
if ($LASTEXITCODE -ne 0) { throw 'Upgrade planning failed; no upgrade is authorized' }

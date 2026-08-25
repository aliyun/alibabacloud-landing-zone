[CmdletBinding()]
param([Parameter(Mandatory)][string]$Manifest,[Parameter(Mandatory)][string]$Fingerprint,[switch]$Automatic)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '..\..\deploying-autowonder-on-alibaba-cloud\scripts\windows\lib.ps1')
$data=Get-ManifestData $Manifest
Assert-VerifiedUpgradeTargets $data
if ($data.upgrade.planFingerprint -ne $Fingerprint) { throw 'Upgrade plan fingerprint mismatch' }
if ($Automatic -and ($data.upgrade.confirmationRequired -or @($data.upgrade.pendingMigrations).Count -gt 0)) { throw 'Automatic approval is forbidden for a migration plan' }
Update-JsonFileAtomic $Manifest { param($document) $document.upgrade.planStatus='approved'; $document.upgrade.approvedPlanFingerprint=$Fingerprint; $document.upgrade.approvedAutomatically=[bool]$Automatic; $document.upgrade.approvedAt=[DateTime]::UtcNow.ToString('o'); $document.status='approved'; $document }
[pscustomobject]@{status='approved';fingerprint=$Fingerprint;automatic=[bool]$Automatic}|ConvertTo-Json -Compress

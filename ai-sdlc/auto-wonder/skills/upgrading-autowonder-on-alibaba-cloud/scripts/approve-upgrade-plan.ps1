#requires -Version 5.1
[CmdletBinding()]
param([Parameter(Mandatory)][string]$Manifest,[Parameter(Mandatory)][string]$Fingerprint,[switch]$Automatic)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'windows\lib.ps1')
$data=Get-ManifestData $Manifest
Assert-VerifiedUpgradeTargets $data
$upgrade = $data['upgrade']
$actual = Get-UpgradeFingerprint -ManifestData $data
if ($Fingerprint -notmatch '^[0-9a-f]{64}$' -or $upgrade['planFingerprint'] -ne $Fingerprint -or $actual -ne $Fingerprint) {
    throw 'Upgrade plan fingerprint mismatch'
}
if (@(Get-ObjectField $upgrade 'blockedReasons').Count -gt 0) { throw 'Blocked upgrade plan cannot be approved' }
if ($Automatic -and (-not $upgrade.ContainsKey('confirmationRequired') -or $upgrade['confirmationRequired'] -or @(Get-ObjectField $upgrade 'pendingMigrations').Count -gt 0)) {
    throw 'Automatic approval is forbidden for a plan requiring confirmation'
}
$mode = if ($Automatic) { 'automatic' } else { 'human' }
$approvedAt = [DateTime]::UtcNow.ToString('o')
$upgrade['approval'] = @{ status='approved'; mode=$mode; planFingerprint=$Fingerprint; approvedAt=$approvedAt }
$upgrade['planStatus']='approved'
$upgrade['approvedPlanFingerprint']=$Fingerprint
$upgrade['approvedAutomatically']=[bool]$Automatic
$upgrade['approvedAt']=$approvedAt
Write-AtomicJson -Path $Manifest -Value $data
@{status='approved';mode=$mode;planFingerprint=$Fingerprint}|ConvertTo-Json -Compress

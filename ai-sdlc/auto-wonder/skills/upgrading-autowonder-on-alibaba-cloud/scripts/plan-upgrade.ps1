[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Manifest,
    [Parameter(Mandatory)][string]$SourceDirectory,
    [Parameter(Mandatory)][string]$EnvFile,
    [switch]$ForceRedeploy
)
$ErrorActionPreference = 'Stop'
$lib = Join-Path $PSScriptRoot '..\..\deploying-autowonder-on-alibaba-cloud\scripts\windows\lib.ps1'
. $lib
$data = Get-ManifestData -Manifest $Manifest
Assert-VerifiedUpgradeTargets -ManifestData $data
$active = [string]$data.deployment.activeCommit
$remote = (& git -C $SourceDirectory remote get-url origin).Trim()
if ($LASTEXITCODE -ne 0 -or $remote -ne [string]$data.repositoryUrl) { throw 'Git origin does not match the deployment repository' }
& git -C $SourceDirectory fetch origin master 2>$null
$target = (& git -C $SourceDirectory rev-parse origin/master).Trim()
if ($LASTEXITCODE -ne 0 -or $target -notmatch '^[0-9a-f]{40}$') { throw 'Exact origin/master commit is unavailable' }
if ($active -eq $target -and -not $ForceRedeploy) { throw 'Deployment is already latest; pass -ForceRedeploy for an explicit same-version redeployment' }
$migrationLines = @(& git -C $SourceDirectory diff --name-status $active $target -- 'docs/migration/*.sql')
$pending = @($migrationLines | Where-Object { $_ -match '^A\s+' } | ForEach-Object {
    $name = ($_ -split '\s+', 2)[1]; [pscustomobject]@{ filename=$name; status='pending' }
})
$planMaterial = [ordered]@{
    fromCommit=$active; toCommit=$target; forceRedeploy=[bool]$ForceRedeploy
    resourceSetFingerprint=[string]$data.upgradeInfo.resourceSetFingerprint
    environmentSha256=Get-FileSha256 -Path $EnvFile
    pendingMigrations=$pending
}
$json = $planMaterial | ConvertTo-Json -Depth 20 -Compress
$sha = [Security.Cryptography.SHA256]::Create()
try { $fingerprint=([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($json)))).Replace('-','').ToLowerInvariant() } finally { $sha.Dispose() }
Update-JsonFileAtomic -Path $Manifest -Update {
    param($document)
    $document.upgrade = [pscustomobject]@{
        fromCommit=$active; toCommit=$target; forceRedeploy=[bool]$ForceRedeploy
        resourceSetFingerprint=[string]$document.upgradeInfo.resourceSetFingerprint
        environmentSha256=$planMaterial.environmentSha256; pendingMigrations=$pending
        confirmationRequired=($pending.Count -gt 0); planFingerprint=$fingerprint
        planStatus='planned'; targetVerification=$data.upgrade.targetVerification
        plannedAt=[DateTime]::UtcNow.ToString('o')
    }
    $document.phase='upgrade-plan'; $document.status='planned'; return $document
}
[pscustomobject]@{status='planned';fromCommit=$active;toCommit=$target;forceRedeploy=[bool]$ForceRedeploy;pendingMigrationCount=$pending.Count;confirmationRequired=($pending.Count -gt 0);fingerprint=$fingerprint}|ConvertTo-Json -Compress

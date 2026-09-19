[CmdletBinding()]
param([Parameter(Mandatory)][string]$Manifest,[Parameter(Mandatory)][string]$EnvFile,[Parameter(Mandatory)][string]$ReleaseDirectory)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'windows-upgrade-common.ps1')
$data=Refresh-ApprovedUpgradeTargets -Manifest $Manifest
Assert-ApprovedUpgradePlan $data; Assert-VerifiedUpgradeTargets $data; Assert-UpgradeBackupCoverage $data
Assert-UpgradeCandidate $data $EnvFile
if (-not $data['runtimeConfig'] -or -not $data.runtimeConfig.prepared -or $data.runtimeConfig.candidateSha256 -ne $data.upgrade.environmentSha256) { throw 'Target runtimeConfig must be prepared before staging' }
if ($data.runtimeConfig.planFingerprint -ne $data.upgrade.planFingerprint -or $data.runtimeConfig.keyGenerationId -ne (Get-UpgradeKeyGeneration $EnvFile)) { throw 'Protected environment generation checkpoint is stale' }
$targetRecommendedRuntimeVersion=[string]$data.upgrade.targetRecommendedRuntimeVersion
$planFingerprint=[string]$data.upgrade.planFingerprint
$release=$data.upgrade['release']
if (-not $release -or $release.commit -ne $data.upgrade.toCommit -or $release.planFingerprint -ne $planFingerprint) { throw 'Release must be built for the approved plan' }
$objects=@();$paths=@{}
foreach ($name in @('auto-wonder.jar','autowonder-schema.sql','autowonder-community-templates.sql','autowonder-migrations.tar.gz','autowonder.service')) {
    $path=Join-Path $ReleaseDirectory $name
    $artifact=$release.artifacts[$name]
    if (-not $artifact -or $artifact.source -ne 'target-source' -or (Get-FileSha256 $path) -ne $artifact.sha256) { throw 'Release artifact differs from sealed target build' }
    $objects+=@{name=$name;sha256=[string]$artifact.sha256};$paths[$name]=$path
}
$objects+=@{name='autowonder.env';sha256=[string]$data.upgrade.environmentSha256};$paths['autowonder.env']=$EnvFile
$bucket=[string]$data.resources['package_bucket']; if (-not $bucket) { $bucket=[string]$data.resources['packageBucket'] }
if ($bucket -notmatch '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$') { throw 'Private package bucket is missing from verified inventory' }
$region=[string]$data.region
$control="https://oss-$region.aliyuncs.com";$runtime="https://oss-$region-internal.aliyuncs.com"
if ($data.resources['oss']) {
    if ($data.resources.oss['control_endpoint']) { $control=[string]$data.resources.oss.control_endpoint }
    elseif ($data.resources.oss['public_endpoint']) { $control=[string]$data.resources.oss.public_endpoint }
    if ($data.resources.oss['runtime_endpoint']) { $runtime=[string]$data.resources.oss.runtime_endpoint }
    elseif ($data.resources.oss['vpc_endpoint']) { $runtime=[string]$data.resources.oss.vpc_endpoint }
}
$prefix="deployments/$($data.deploymentId)/upgrade-$([Guid]::NewGuid().ToString('N'))"
$session=New-UpgradeOssSession $data
$uploaded=@();$nodes=@();$cleanupFailed=$false
try {
    foreach ($item in $objects) {
        $data=Refresh-ApprovedUpgradeTargets -Manifest $Manifest
        if ($data.upgrade.planFingerprint -ne $planFingerprint) { throw 'Approved plan changed during staging' }
        $object="oss://$bucket/$prefix/$($item.name)"
        # Record before upload so even a transfer error gets a cleanup attempt.
        $uploaded+=$object
        Invoke-UpgradeOss $session upload $object $control $paths[$item.name]
    }
    foreach ($instanceId in Get-ManifestInstanceIds $data) {
        $data=Refresh-ApprovedUpgradeTargets -Manifest $Manifest
        if ($data.upgrade.planFingerprint -ne $planFingerprint) { throw 'Approved plan changed during staging' }
        Assert-UpgradeBackupCoverage $data
        $request=New-UpgradeRemoteRequest $data 'stage-upgrade'
        $request.envSha=[string]$data.upgrade.environmentSha256;$request.runtime=$targetRecommendedRuntimeVersion
        $request.keyGenerationId=[string]$data.runtimeConfig.keyGenerationId
        $request.backupSha=Get-UpgradeBackupSha $data $instanceId
        $request.objects=@($objects | ForEach-Object { @{name=$_.name;sha256=$_.sha256;url=(Invoke-UpgradeOss $session sign "oss://$bucket/$prefix/$($_.name)" $runtime)} })
        $result=Invoke-UpgradePayload $data $instanceId $request -ManifestPath $Manifest
        $commit=Get-UpgradeResultValue $result 'STAGED_COMMIT' '[0-9a-f]{40}'
        if ($commit -ne $data.upgrade.toCommit) { throw 'Staged commit differs from approved target' }
        $nodes+=@{instanceId=$instanceId;invocationId=$result.invocationId;status='verified';commit=$commit}
        Update-JsonFileAtomic $Manifest {param($document)$document.upgrade.staging=@{status='running';commit=$commit;planFingerprint=$document.upgrade.planFingerprint;nodes=$nodes};$document}
    }
    Update-JsonFileAtomic $Manifest {param($document)$document.upgrade.staging.status='verified';$document.upgrade.staging.verifiedAt=[DateTime]::UtcNow.ToString('o');$document.phase='upgrade-stage';$document.status='staged';$document}
} finally {
    foreach ($object in $uploaded) {
        try { $null=Refresh-ApprovedUpgradeTargets -Manifest $Manifest; Invoke-UpgradeOss $session remove $object $control } catch { $cleanupFailed=$true }
    }
    Remove-Item -LiteralPath $session.directory -Recurse -Force
    if ($cleanupFailed) {
        Update-JsonFileAtomic $Manifest {param($document)$document.upgrade.stagingCleanup=@{required=$true;bucket=$bucket;prefix=$prefix};$document}
        Write-Warning 'Private staging cleanup is incomplete; the sanitized manifest records the remaining prefix'
    }
}
@{status='staged';commit=$data.upgrade.toCommit;nodeCount=$nodes.Count;stagingCleaned=(-not $cleanupFailed)}|ConvertTo-Json -Compress

[CmdletBinding()]
param(
    [Parameter(Mandatory,Position=0)][ValidateSet('upgrade-inventory')][string]$Operation,
    [Parameter(Mandatory)][string]$Manifest
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'windows-upgrade-common.ps1')
if ($Operation -eq 'upgrade-inventory') {
    Update-JsonFileAtomic $Manifest {param($document)$document.upgradeInventory=@{status='checking';nodes=@()};$document}
    & (Join-Path $PSScriptRoot 'verify-deployment-targets.ps1') -Manifest $Manifest | Out-Null
    $data=Get-ManifestData $Manifest
    Assert-VerifiedUpgradeTargets $data
}
$instanceIds=@(Get-ManifestInstanceIds $data)
if ($instanceIds.Count -eq 0) { throw 'Verified ECS inventory is empty' }

switch ($Operation) {
    'upgrade-inventory' {
        $expected=[string]$data.deployment.activeCommit
        if ($expected -notmatch '^[0-9a-f]{40}$') { throw 'An immutable active release identity is required' }
        $nodes=@();$baselineHashes=$null
        foreach ($instanceId in $instanceIds) {
            $request=@{operation=$Operation;from=$expected}
            $result=Invoke-UpgradePayload $data $instanceId $request -ManifestPath $Manifest
            $active=Get-UpgradeResultValue $result 'ACTIVE_COMMIT' '[0-9a-f]{40}'
            $jarSha=Get-UpgradeResultValue $result 'JAR_SHA256' '[0-9a-f]{64}'
            $migrationsSha=Get-UpgradeResultValue $result 'MIGRATIONS_SHA256' '[0-9a-f]{64}'
            if ($active -ne $expected) { throw 'Active release inventory mismatch' }
            $hashes="$jarSha/$migrationsSha"
            if ($baselineHashes -and $baselineHashes -ne $hashes) { throw 'ECS nodes disagree on active release artifacts' }
            $baselineHashes=$hashes
            $nodes+=@{instanceId=$instanceId;commit=$active;activeCommitPrefix=$active.Substring(0,12);jarSha256=$jarSha;migrationsSha256=$migrationsSha;invocationId=$result.invocationId;status='verified'}
        }
        Update-JsonFileAtomic $Manifest {param($document)
            $document.upgradeInventory=@{status='verified';activeCommit=$expected;nodes=$nodes;resourceSetFingerprint=$document.upgradeInfo.resourceSetFingerprint;targetVerificationFingerprint=$document.upgrade.targetVerification.fingerprint;verifiedAt=[DateTime]::UtcNow.ToString('o');verifiedEpoch=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()}
            $document.phase='upgrade-inventory';$document.status='ready';$document
        }
    }
}
@{status='verified';operation=$Operation}|ConvertTo-Json -Compress

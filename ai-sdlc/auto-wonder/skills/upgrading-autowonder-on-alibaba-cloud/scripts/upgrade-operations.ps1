[CmdletBinding()]
param(
    [Parameter(Mandatory,Position=0)][ValidateSet('upgrade-inventory','upgrade-backup','runtime-config','maintenance-stop','database-migrate','rolling-upgrade','acceptance','rollback-upgrade')][string]$Operation,
    [Parameter(Mandatory)][string]$Manifest,
    [string]$EnvFile,
    [string]$TerraformDirectory,
    [switch]$ConfirmMigrations,
    [switch]$ConfirmRollingCompatible,
    [switch]$ConfirmRollback
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'windows-upgrade-common.ps1')
if ($Operation -eq 'upgrade-inventory') {
    Update-JsonFileAtomic $Manifest {param($document)$document.upgradeInventory=@{status='checking';nodes=@()};$document}
    & (Join-Path $PSScriptRoot 'verify-deployment-targets.ps1') -Manifest $Manifest | Out-Null
    $data=Get-ManifestData $Manifest
    Assert-VerifiedUpgradeTargets $data
} else {
    $data=Refresh-ApprovedUpgradeTargets -Manifest $Manifest
    Assert-ApprovedUpgradePlan $data; Assert-VerifiedUpgradeTargets $data
}
$instanceIds=@(Get-ManifestInstanceIds $data)
$planFingerprint=[string]$data.upgrade['planFingerprint']
if ($instanceIds.Count -eq 0) { throw 'Verified ECS inventory is empty' }

function Assert-MaintenanceCheckpoint($Data) {
    $checkpoint=$Data.upgrade['maintenance']
    $expected=@(Get-ManifestInstanceIds $Data | Sort-Object)
    if ($Data.upgrade.executionMode -ne 'maintenance' -or -not $checkpoint -or $checkpoint.status -ne 'stopped' -or $checkpoint.planFingerprint -ne $Data.upgrade.planFingerprint -or (($checkpoint.instanceIds | Sort-Object) -join ',') -ne ($expected -join ',')) { throw 'Maintenance checkpoint must cover this approved plan and every target' }
}
function Test-AllMaintenanceNodes($Data, [bool]$AllowPassed=$false) {
    Assert-MaintenanceCheckpoint $Data
    $rollout=$Data['rollingUpgrade']
    foreach ($node in @(Get-ManifestInstanceIds $Data)) {
        $passed=($AllowPassed -and $null -ne $rollout -and $rollout['planFingerprint'] -eq $Data.upgrade.planFingerprint -and $rollout['commit'] -eq $Data.upgrade.toCommit -and @($rollout['nodes'] | Where-Object { $_.instanceId -eq $node -and $_.status -eq 'passed' }).Count -eq 1)
        if ($passed) {
            $request=New-UpgradeRemoteRequest $Data 'rolling-upgrade'
            $request.maintenance=$true;$request.resumePassed=$true
            $request.jarSha=[string]$Data.upgrade.release.artifacts['auto-wonder.jar'].sha256
            $request.unitSha=[string]$Data.upgrade.release.artifacts['autowonder.service'].sha256
            $request.envSha=[string]$Data.upgrade.environmentSha256
            $request.runtime=[string]$Data.runtimeConfig.recommendedRuntimeVersion
            $request.backupSha=Get-UpgradeBackupSha $Data $node
            $result=Invoke-UpgradePayload $Data $node $request -ManifestPath $Manifest
            $actual=Get-UpgradeResultValue $result 'ROLLOUT_COMMIT' '[0-9a-f]{40}'
            if ($actual -ne $Data.upgrade.toCommit) { throw 'Previously passed node target changed' }
            continue
        }
        $result=Invoke-UpgradePayload $Data $node (New-UpgradeRemoteRequest $Data 'maintenance-verify') -ManifestPath $Manifest
        Get-UpgradeResultValue $result 'MAINTENANCE_STATUS' 'stopped' | Out-Null
    }
}

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
    'upgrade-backup' {
        $nodes=@()
        foreach ($instanceId in $instanceIds) {
            $data=Refresh-ApprovedUpgradeTargets -Manifest $Manifest
            if ($data.upgrade.planFingerprint -ne $planFingerprint) { throw 'Approved plan changed during backup' }
            $result=Invoke-UpgradePayload $data $instanceId (New-UpgradeRemoteRequest $data $Operation) -ManifestPath $Manifest
            $sha=Get-UpgradeResultValue $result 'BACKUP_SHA256' '[0-9a-f]{64}'
            $nodes+=@{instanceId=$instanceId;invocationId=$result.invocationId;status='verified';sha256=$sha}
            Update-JsonFileAtomic $Manifest {param($document)
                $document.upgrade.backup=@{status='running';nodes=$nodes;planFingerprint=$planFingerprint;fromCommit=$document.upgrade.fromCommit;targetCommit=$document.upgrade.toCommit}
                $document
            }
        }
        Update-JsonFileAtomic $Manifest {param($document)$document.upgrade.backup.status='verified';$document.upgrade.backup.verifiedAt=[DateTime]::UtcNow.ToString('o');$document.phase='upgrade-backup';$document.status='backed-up';$document}
    }
    'runtime-config' {
        Assert-UpgradeCandidate $data $EnvFile
        $targetRecommendedRuntimeVersion=[string]$data.upgrade.targetRecommendedRuntimeVersion
        Update-JsonFileAtomic $Manifest {param($document)
            $document.runtimeConfig=@{prepared=$true;valuesValidated=$true;recommendedRuntimeVersion=$targetRecommendedRuntimeVersion;candidateSha256=(Get-FileSha256 $EnvFile);planFingerprint=$planFingerprint}
            $document.phase='runtime-config';$document.status='prepared';$document
        }
    }
    'maintenance-stop' {
        if ($data.upgrade.executionMode -ne 'maintenance') { throw 'Approved maintenance execution mode is required' }
        Assert-UpgradeStaging $data
        Assert-UpgradeBackupCoverage $data
        Update-JsonFileAtomic $Manifest {param($document)$document.upgrade.maintenance=@{status='stopping';planFingerprint=$planFingerprint;instanceIds=$instanceIds};$document}
        foreach ($instanceId in $instanceIds) {
            $result=Invoke-UpgradePayload $data $instanceId (New-UpgradeRemoteRequest $data 'maintenance-stop') -ManifestPath $Manifest
            Get-UpgradeResultValue $result 'MAINTENANCE_STATUS' 'stopped' | Out-Null
        }
        Update-JsonFileAtomic $Manifest {param($document)$document.upgrade.maintenance.status='stopped';$document}
        $data=Get-ManifestData $Manifest
        Test-AllMaintenanceNodes $data
    }
    'database-migrate' {
        $pending=@($data.upgrade.pendingMigrations | Sort-Object { [long]$_.version })
        if ($pending.Count -eq 0) {
            Update-JsonFileAtomic $Manifest {param($document)$document.upgrade.databaseMigration=@{status='not-required';applied=@();planFingerprint=$planFingerprint};$document.phase='database-migrate';$document.status='ready';$document}
            break
        }
        Assert-UpgradeStaging $data
        if (-not $ConfirmMigrations -or ($data.upgrade.executionMode -ne 'maintenance' -and -not $ConfirmRollingCompatible)) { throw 'Explicit migration and rolling compatibility confirmation is required' }
        $existing=$data.upgrade['databaseMigration']
        if ($existing -and $existing.status -in @('running','failed')) { throw 'An interrupted or failed migration requires reviewed recovery before retry' }
        foreach ($migration in $pending) {
            if ($data.upgrade.executionMode -ne 'maintenance' -and ($migration['maintenanceRequired'] -or $migration['destructive'] -or @($migration.riskOperations | Where-Object { $_ -in @('DROP','TRUNCATE','RENAME') }).Count -gt 0)) { throw 'Destructive migrations require a maintenance workflow' }
        }
        $backup=$data.upgrade['databaseBackup']
        $rdsId=[string]$data.resources['rds_instance_id'];if (-not $rdsId -and $data.resources['rds']) { $rdsId=[string]$data.resources.rds['instance_id'] }
        $epoch=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
        if (-not $backup -or $backup.status -ne 'verified' -or -not $backup.backupId -or $backup.rdsInstanceId -ne $rdsId -or $backup.planFingerprint -ne $planFingerprint -or ($epoch-[long]$backup.verifiedEpoch) -gt 86400 -or [long]$backup.verifiedEpoch -gt $epoch) { throw 'Recent verified RDS backup bound to this instance and plan is required' }
        $completed=[DateTimeOffset]::MinValue
        if (-not [DateTimeOffset]::TryParse([string]$backup.completedAt,[ref]$completed) -or $completed -lt [DateTimeOffset]::UtcNow.AddDays(-7) -or $completed -gt [DateTimeOffset]::UtcNow) { throw 'RDS backup completion is outside the accepted time window' }
        $data=Refresh-ApprovedUpgradeTargets -Manifest $Manifest
        if ($data.upgrade.planFingerprint -ne $planFingerprint) { throw 'Approved plan changed before migration' }
        if ($data.upgrade.executionMode -eq 'maintenance') { Test-AllMaintenanceNodes $data }
        # Persist before submission: an interrupted or partly applied migration blocks rollback.
        Update-JsonFileAtomic $Manifest {param($document)
            $document.upgrade.databaseMutationStarted=$true
            $document.upgrade.databaseMigration=@{status='running';applied=@();planFingerprint=$planFingerprint}
            if ($document.upgrade.executionMode -eq 'maintenance') { $document.upgrade.databaseCompatibility.status='maintenance';$document.upgrade.databaseCompatibility.rollingAllowed=$false } else { $document.upgrade.databaseCompatibility=@{status='rolling-compatible';rollingAllowed=$true;destructive=$false} }
            $document
        }
        try {
            $request=New-UpgradeRemoteRequest $data $Operation;$request.migrations=$pending;$request.maintenance=($data.upgrade.executionMode -eq 'maintenance')
            $result=Invoke-UpgradePayload $data $instanceIds[0] $request -ManifestPath $Manifest
            $count=Get-UpgradeResultValue $result 'MIGRATIONS_APPLIED' '[0-9]+'
            if ([int]$count -ne $pending.Count) { throw 'Migration completion count differs from approved plan' }
            Update-JsonFileAtomic $Manifest {param($document)$document.upgrade.databaseMigration=@{status='passed';applied=$pending;invocationId=$result.invocationId;planFingerprint=$planFingerprint};$document.phase='database-migrate';$document.status='ready';$document}
        } catch {
            Update-JsonFileAtomic $Manifest {param($document)$document.upgrade.databaseMigration.status='failed';$document.phase='database-migrate';$document.status='failed';$document}
            throw 'Database migration failed or remote state is unknown; activation and application rollback remain blocked'
        }
    }
    'rolling-upgrade' {
        Assert-UpgradeStaging $data
        $runtimeConfig=$data.runtimeConfig
        $migration=$data.upgrade['databaseMigration']
        if (-not $migration -or $migration.status -notin @('passed','applied','not-required') -or $migration.planFingerprint -ne $planFingerprint) { throw 'Database migration checkpoint is incomplete or stale' }
        if (@($data.upgrade.pendingMigrations).Count -gt 0 -and ($migration.status -notin @('passed','applied') -or ($data.upgrade.executionMode -ne 'maintenance' -and -not $data.upgrade.databaseCompatibility.rollingAllowed))) { throw 'Pending database migrations are not applied or rolling compatible' }
        if ($data.upgrade.executionMode -eq 'maintenance') { Test-AllMaintenanceNodes $data $true }
        $previousRollout=$data['rollingUpgrade']
        $nodes=@();$target=[string]$data.upgrade.toCommit
        if ($null -ne $previousRollout -and $previousRollout['planFingerprint'] -eq $planFingerprint -and $previousRollout['commit'] -eq $target) {
            $nodes=@($previousRollout['nodes'] | Where-Object { $_.status -eq 'passed' -and $_.instanceId -in $instanceIds })
        }
        foreach ($instanceId in $instanceIds) {
            $data=Refresh-ApprovedUpgradeTargets -Manifest $Manifest
            if ($data.upgrade.planFingerprint -ne $planFingerprint) { throw 'Approved plan changed during rollout' }
            Assert-UpgradeStaging $data
            $request=New-UpgradeRemoteRequest $data $Operation
            $request.maintenance=($data.upgrade.executionMode -eq 'maintenance')
            $request.resumePassed=($request.maintenance -and $null -ne $previousRollout -and $previousRollout['planFingerprint'] -eq $planFingerprint -and $previousRollout['commit'] -eq $target -and @($previousRollout['nodes'] | Where-Object { $_.instanceId -eq $instanceId -and $_.status -eq 'passed' }).Count -eq 1)
            $request.jarSha=[string]$data.upgrade.release.artifacts['auto-wonder.jar'].sha256
            $request.unitSha=[string]$data.upgrade.release.artifacts['autowonder.service'].sha256
            $request.envSha=[string]$data.upgrade.environmentSha256
            $request.runtime=[string]$data.runtimeConfig.recommendedRuntimeVersion
            $request.backupSha=Get-UpgradeBackupSha $data $instanceId
            try {
                $result=Invoke-UpgradePayload $data $instanceId $request -ManifestPath $Manifest
                $commit=Get-UpgradeResultValue $result 'ROLLOUT_COMMIT' '[0-9a-f]{40}'
                if ($commit -ne $target) { throw 'Activated commit differs from approved target' }
                $nodes=@($nodes | Where-Object { $_.instanceId -ne $instanceId }) + @(@{instanceId=$instanceId;invocationId=$result.invocationId;status='passed';commit=$target})
            } catch {
                $nodes=@($nodes | Where-Object { $_.instanceId -ne $instanceId }) + @(@{instanceId=$instanceId;status='failed';commit=$target})
                Update-JsonFileAtomic $Manifest {param($document)$document.rollingUpgrade=@{status='partial';commit=$target;nodes=$nodes;planFingerprint=$planFingerprint};$document.status='failed';$document}
                throw 'ECS-local rolling upgrade failed; remaining nodes were not restarted'
            }
            Update-JsonFileAtomic $Manifest {param($document)$document.rollingUpgrade=@{status='running';commit=$target;nodes=$nodes;planFingerprint=$planFingerprint};$document}
        }
        Update-JsonFileAtomic $Manifest {param($document)
            $document.rollingUpgrade=@{status='passed';commit=$target;nodes=$nodes;nodeOrder='sequential';planFingerprint=$planFingerprint;completedAt=[DateTime]::UtcNow.ToString('o')}
            $activeBaseline=$document.deployment['activeReleaseBaseline']
            if ($activeBaseline -and $activeBaseline.releaseId -eq $document.upgrade.fromCommit -and -not $document.upgrade['previousReleaseBaseline']) {
                $document.upgrade.previousReleaseBaseline=$activeBaseline
            }
            $document.deployment.activeReleaseBaseline=@{releaseId=$target;releaseVersion=$document.releaseVersion;source=$document.source;artifacts=$document.artifacts}
            if ($document.localContext['candidateEnvFile']) {
                if (-not $document.localContext['previousActiveEnvFile']) { $document.localContext.previousActiveEnvFile=$document.localContext['activeEnvFile'] }
                $document.localContext.activeEnvFile=$document.localContext.candidateEnvFile
            }
            $document.acceptance=@{ecsLocalHealth='passed'};$document.deployment.activeCommit=$target
            if ($document.upgrade['sourceRepositoryUrl']) { $document.repositoryUrl=$document.upgrade['sourceRepositoryUrl'] }
            $document.phase='upgrade-acceptance';$document.status='accepted';$document
        }
    }
    'acceptance' {
        if (-not $data['rollingUpgrade'] -or $data.rollingUpgrade.status -ne 'passed' -or $data.rollingUpgrade.commit -ne $data.upgrade.toCommit -or $data.rollingUpgrade.planFingerprint -ne $planFingerprint -or $data.acceptance.ecsLocalHealth -ne 'passed') { throw 'Complete ECS-local rolling acceptance is missing' }
    }
    'rollback-upgrade' {
        if (-not $ConfirmRollback) { throw 'Explicit rollback confirmation is required' }
        $migration=$data.upgrade['databaseMigration']
        if ($data.upgrade['databaseMutationStarted'] -or ($migration -and ($migration.status -in @('running','failed','passed','applied') -or @($migration.applied).Count -gt 0))) { throw 'Application rollback is blocked after database migration started, including partial failure' }
        Assert-UpgradeBackupCoverage $data
        $nodes=@();$from=[string]$data.upgrade.fromCommit
        foreach ($instanceId in $instanceIds) {
            $data=Refresh-ApprovedUpgradeTargets -Manifest $Manifest
            if ($data.upgrade.planFingerprint -ne $planFingerprint) { throw 'Approved plan changed during rollback' }
            $request=New-UpgradeRemoteRequest $data $Operation;$request.backupSha=Get-UpgradeBackupSha $data $instanceId
            try {
                $result=Invoke-UpgradePayload $data $instanceId $request -ManifestPath $Manifest
                $restored=Get-UpgradeResultValue $result 'ROLLBACK_RELEASE' '[0-9a-f]{12}'
                if ($restored -ne $from.Substring(0,12)) { throw 'Rollback restored an unexpected release' }
                $nodes+=@{instanceId=$instanceId;invocationId=$result.invocationId;status='passed'}
            } catch {
                $nodes+=@{instanceId=$instanceId;status='failed'}
                Update-JsonFileAtomic $Manifest {param($document)$document.upgrade.rollback=@{status='partial';nodes=$nodes;confirmed=$true};$document.status='failed';$document}
                throw 'Rollback stopped after a node failed; inspect the partial rollback checkpoint'
            }
            Update-JsonFileAtomic $Manifest {param($document)$document.upgrade.rollback=@{status='running';nodes=$nodes;confirmed=$true};$document}
        }
        Update-JsonFileAtomic $Manifest {param($document)
            $previous=$document.upgrade['previousReleaseBaseline']
            if (-not $previous -or $previous.releaseId -ne $from) { $previous=$document.deployment['activeReleaseBaseline'] }
            if ($previous -and $previous.releaseId -eq $from) {
                $document.deployment.activeReleaseBaseline=$previous;$document.releaseVersion=$previous.releaseVersion
                $document.source=$previous.source;$document.artifacts=$previous.artifacts
            }
            $activeEnv=$document.localContext['previousActiveEnvFile']
            if (-not $activeEnv) { $activeEnv=$document.localContext['activeEnvFile'] }
            if ($activeEnv) {
                $document.localContext.activeEnvFile=$activeEnv
                $document.localContext.protectedEnvFile=$activeEnv
            }
            $document.upgrade.rollback.status='passed';$document.deployment.activeCommit=$from;if ($document.upgrade['previousRepositoryUrl']) { $document.repositoryUrl=$document.upgrade['previousRepositoryUrl'] };$document.phase='rollback-upgrade';$document.status='rolled-back';$document}
    }
}
@{operation=$Operation;status='passed';nodeCount=$instanceIds.Count}|ConvertTo-Json -Compress

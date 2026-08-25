[CmdletBinding()]
param(
    [Parameter(Mandatory,Position=0)][ValidateSet('upgrade-inventory','upgrade-backup','runtime-config','database-migrate','rolling-upgrade','acceptance','rollback-upgrade')][string]$Operation,
    [Parameter(Mandatory)][string]$Manifest,
    [string]$EnvFile,
    [string]$TerraformDirectory,
    [switch]$ConfirmMigrations,
    [switch]$ConfirmRollingCompatible,
    [switch]$ConfirmRollback
)
$ErrorActionPreference='Stop'
$deployWindows=Join-Path $PSScriptRoot '..\..\deploying-autowonder-on-alibaba-cloud\scripts\windows'
. (Join-Path $deployWindows 'lib.ps1'); . (Join-Path $deployWindows 'cloud-assistant.ps1')
$data=Get-ManifestData $Manifest
$instanceIds=@(Get-ManifestInstanceIds $data)

if($Operation -ne 'upgrade-inventory') { Assert-ApprovedUpgradePlan $data; Assert-VerifiedUpgradeTargets $data }

switch($Operation){
 'upgrade-inventory' {
    Assert-VerifiedUpgradeTargets $data
    $expected=[string]$data.deployment.activeCommit; $prefix=$expected.Substring(0,12); $nodes=@()
    foreach ($instanceId in $instanceIds) {
        $script=@"
set -euo pipefail
active=`$(readlink -f /opt/autowonder/current)
test "`${active##*/}" = '$prefix'
printf 'ACTIVE_COMMIT=$expected\n'
"@
        $result=Invoke-AutoWonderCloudCommand -ManifestData $data -InstanceId $instanceId -Script $script
        if($result.output -notmatch "ACTIVE_COMMIT=$expected"){throw 'Active release inventory mismatch'}
        $nodes += [pscustomobject]@{instanceId=$instanceId;commit=$expected;invocationId=$result.invocationId;status='verified'}
    }
    Update-JsonFileAtomic $Manifest {param($document)$document.upgrade.inventory=[pscustomobject]@{status='verified';nodes=$nodes;verifiedAt=[DateTime]::UtcNow.ToString('o')};$document}
 }
 'upgrade-backup' {
    $nodes=@()
    foreach($instanceId in $instanceIds){
        $script=@'
set -euo pipefail
tmp=/opt/autowonder/.upgrade-rollback-backup.tar.gz.tmp
target=/opt/autowonder/upgrade-rollback-backup.tar.gz
rm -f "$tmp"
tar -czf "$tmp" -C / opt/autowonder/current etc/autowonder/autowonder.env etc/systemd/system/autowonder.service
tar -tzf "$tmp" >/dev/null
sha256sum "$tmp" > "${tmp}.sha256"
mv -f "$tmp" "$target"
mv -f "${tmp}.sha256" "${target}.sha256"
printf 'BACKUP_VERIFIED\n'
'@
        $result=Invoke-AutoWonderCloudCommand -ManifestData $data -InstanceId $instanceId -Script $script
        if($result.output -notmatch 'BACKUP_VERIFIED'){throw 'Upgrade backup verification failed'}
        $nodes += [pscustomobject]@{instanceId=$instanceId;invocationId=$result.invocationId;status='verified'}
    }
    Update-JsonFileAtomic $Manifest {param($document)$document.upgrade.backup=[pscustomobject]@{status='verified';nodes=$nodes;planFingerprint=$document.upgrade.planFingerprint;verifiedAt=[DateTime]::UtcNow.ToString('o')};$document.phase='upgrade-backup';$document.status='backed-up';$document}
 }
 'runtime-config' {
    if(-not $EnvFile -or -not (Test-Path -LiteralPath $EnvFile)){throw 'Protected candidate environment file is required'}
    if((Get-FileSha256 $EnvFile) -ne [string]$data.upgrade.environmentSha256){throw 'Candidate environment hash differs from approved plan'}
    $required=@('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','REDIS_HOST','AUTOWONDER_SECRET_MASTER_KEY','AUTOWONDER_JWT_SECRET')
    $content=Get-Content -LiteralPath $EnvFile
    foreach($key in $required){if(-not @($content|Where-Object{$_ -match "^$key=.+"})){throw "Required environment key missing: $key"}}
    Update-JsonFileAtomic $Manifest {param($document)$document.runtimeConfig=[pscustomobject]@{prepared=$true;valuesValidated=$true;candidateSha256=(Get-FileSha256 $EnvFile)};$document.phase='runtime-config';$document.status='prepared';$document}
 }
 'database-migrate' {
    $pending=@($data.upgrade.pendingMigrations)
    if($pending.Count -gt 0){
        if(-not $ConfirmMigrations -or -not $ConfirmRollingCompatible){throw 'Explicit migration and rolling compatibility confirmation is required'}
        if($data.upgrade.databaseBackup.status -ne 'verified'){throw 'Verified RDS backup is required'}
        throw 'Native Windows migration execution requires a reviewed non-empty migration implementation'
    }
    Update-JsonFileAtomic $Manifest {param($document)$document.upgrade.databaseMigration=[pscustomobject]@{status='not-required';applied=@()};$document.phase='database-migrate';$document.status='ready';$document}
 }
 'rolling-upgrade' {
    Assert-UpgradeBackupCoverage $data
    if($data.upgrade.staging.status -ne 'verified'){throw 'Verified staged release is required'}
    if(@($data.upgrade.pendingMigrations).Count -gt 0 -and $data.upgrade.databaseMigration.status -ne 'applied'){throw 'Pending database migrations are not applied'}
    $target=[string]$data.upgrade.toCommit; $prefix=$target.Substring(0,12); $nodes=@()
    foreach ($instanceId in $instanceIds) {
        $script=@"
set -euo pipefail
test -d /opt/autowonder/releases/$prefix
ln -sfn /opt/autowonder/releases/$prefix /opt/autowonder/current
systemctl daemon-reload
systemctl restart autowonder.service
for attempt in `$(seq 1 90); do
  body=`$(curl --fail --silent --connect-timeout 2 --max-time 5 http://127.0.0.1:7001/checkpreload.htm 2>/dev/null || true)
  if systemctl is-active --quiet autowonder.service && ss -ltnH 'sport = :7001' | grep -q . && [ "`$body" = success ] && curl --fail --silent --max-time 5 http://127.0.0.1:7001/api/platform/branding/public >/dev/null; then
    printf 'ROLLOUT_COMMIT=$target\n'; exit 0
  fi
  sleep 2
done
exit 1
"@
        $result=Invoke-AutoWonderCloudCommand -ManifestData $data -InstanceId $instanceId -Script $script
        if($result.output -notmatch "ROLLOUT_COMMIT=$target"){throw 'ECS-local rolling upgrade verification failed'}
        $nodes += [pscustomobject]@{instanceId=$instanceId;invocationId=$result.invocationId;status='passed';commit=$target}
    }
    Update-JsonFileAtomic $Manifest {param($document)$document.rollingUpgrade=[pscustomobject]@{status='passed';commit=$target;nodes=$nodes;nodeOrder='sequential';completedAt=[DateTime]::UtcNow.ToString('o')};$document.acceptance=[pscustomobject]@{ecsLocalHealth='passed'};$document.deployment.activeCommit=$target;$document.phase='upgrade-acceptance';$document.status='accepted';$document}
 }
 'acceptance' {
    if($data.rollingUpgrade.status -ne 'passed' -or $data.acceptance.ecsLocalHealth -ne 'passed'){throw 'Complete ECS-local rolling acceptance is missing'}
 }
 'rollback-upgrade' {
    if(-not $ConfirmRollback){throw 'Explicit rollback confirmation is required'}
    if($data.upgrade.databaseMigration.status -eq 'applied'){throw 'Automatic application rollback is blocked after database migration'}
    throw 'Rollback execution is intentionally unavailable without a separately reviewed recovery operation'
 }
}
[pscustomobject]@{operation=$Operation;status='passed';nodeCount=$instanceIds.Count}|ConvertTo-Json -Compress

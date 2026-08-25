[CmdletBinding()]
param([Parameter(Mandatory)][string]$Manifest)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '..\..\deploying-autowonder-on-alibaba-cloud\scripts\windows\lib.ps1')
$data=Get-ManifestData $Manifest; Assert-ApprovedUpgradePlan $data; Assert-VerifiedUpgradeTargets $data
$profile=if($data.cloudProfile){[string]$data.cloudProfile}else{'default'}
$rdsId=[string]$data.resources.rds.instance_id
if(-not $rdsId){throw 'RDS instance identity is missing'}
$response=Invoke-AliyunJson -Product rds -Action DescribeBackups -Profile $profile -Parameters @{RegionId=$data.region;DBInstanceId=$rdsId;PageSize=30}
$backup=@($response.Items.Backup|Where-Object{$_.BackupStatus -eq 'Success'}|Sort-Object BackupEndTime -Descending)[0]
if(-not $backup){throw 'No successful RDS backup is available'}
Update-JsonFileAtomic $Manifest {param($document)$document.upgrade.databaseBackup=[pscustomobject]@{status='verified';instanceId=$rdsId;backupId=[string]$backup.BackupId;completedAt=[string]$backup.BackupEndTime;verifiedAt=[DateTime]::UtcNow.ToString('o')};$document}
[pscustomobject]@{status='verified';instanceId=$rdsId;backupId=[string]$backup.BackupId}|ConvertTo-Json -Compress

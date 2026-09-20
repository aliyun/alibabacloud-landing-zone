[CmdletBinding()]
param([Parameter(Mandatory)][string]$Manifest)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'windows-upgrade-common.ps1')
$data=Refresh-ApprovedUpgradeTargets -Manifest $Manifest
$rdsId=[string]$data.resources['rds_instance_id']
if (-not $rdsId -and $data.resources['rds']) { $rdsId=[string]$data.resources.rds['instance_id'] }
if (-not $rdsId) { throw 'RDS instance identity is missing' }
$end=[DateTime]::UtcNow;$start=$end.AddDays(-7);$page=1;$backups=@()
do {
    $response=Invoke-AliyunJson -Product rds -Action DescribeBackups -Profile 'auto-wonder' -Parameters @{RegionId=$data.region;DBInstanceId=$rdsId;StartTime=$start.ToString('yyyy-MM-ddTHH:mmZ');EndTime=$end.ToString('yyyy-MM-ddTHH:mmZ');PageSize=100;PageNumber=$page}
    $items=Get-ObjectField $response 'Items'
    if (-not $items) { $items=Get-ObjectField $response 'Backups' }
    $entries=@(Get-ObjectField $items 'Backup')
    foreach ($entry in $entries) {
        if (-not $entry -or (Get-ObjectField $entry 'BackupStatus') -ne 'Success') { continue }
        $completed=[DateTimeOffset]::MinValue
        if (-not [DateTimeOffset]::TryParse([string](Get-ObjectField $entry 'BackupEndTime'),[ref]$completed)) { continue }
        if ($completed.UtcDateTime -lt $start -or $completed.UtcDateTime -gt $end) { continue }
        if (-not (Get-ObjectField $entry 'BackupId')) { continue }
        $backups+=@{id=[string](Get-ObjectField $entry 'BackupId');completed=$completed}
    }
    $total=[int](Get-ObjectField $response 'TotalRecordCount')
    if ($page*100 -lt $total -and @($entries | Where-Object { $null -ne $_ }).Count -eq 0) { throw 'RDS backup pagination ended before total count' }
    $page++
} while (($page-1)*100 -lt $total)
$selected=@($backups | Sort-Object { $_.completed } -Descending)
if ($selected.Count -eq 0) { throw 'No successful RDS backup completed within the last seven days' }
$backup=$selected[0]
Update-JsonFileAtomic $Manifest {param($document)
    $document.upgrade.databaseBackup=@{status='verified';rdsInstanceId=$rdsId;instanceId=$rdsId;backupId=$backup.id;completedAt=$backup.completed.UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ssZ');planFingerprint=$document.upgrade.planFingerprint;verifiedAt=[DateTime]::UtcNow.ToString('o');verifiedEpoch=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()}
    $document
}
@{status='verified';rdsInstanceId=$rdsId;backupId=$backup.id}|ConvertTo-Json -Compress

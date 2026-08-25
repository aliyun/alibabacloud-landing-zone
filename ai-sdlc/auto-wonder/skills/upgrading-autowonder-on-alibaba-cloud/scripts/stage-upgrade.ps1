[CmdletBinding()]
param([Parameter(Mandatory)][string]$Manifest,[Parameter(Mandatory)][string]$EnvFile,[Parameter(Mandatory)][string]$ReleaseDirectory)
$ErrorActionPreference='Stop'
$deployWindows=Join-Path $PSScriptRoot '..\..\deploying-autowonder-on-alibaba-cloud\scripts\windows'
. (Join-Path $deployWindows 'lib.ps1'); . (Join-Path $deployWindows 'cloud-assistant.ps1')
$data=Get-ManifestData $Manifest; Assert-ApprovedUpgradePlan $data; Assert-VerifiedUpgradeTargets $data; Assert-UpgradeBackupCoverage $data
if ((Get-FileSha256 $EnvFile) -ne [string]$data.upgrade.environmentSha256) { throw 'Candidate environment hash differs from the approved plan' }
$target=[string]$data.upgrade.toCommit; $prefix=$target.Substring(0,12)
$nodes=@()
foreach($instanceId in Get-ManifestInstanceIds $data){
    $script=@"
set -euo pipefail
release=/opt/autowonder/releases/$prefix
test -d "`$release"
test -f "`$release/auto-wonder.jar"
printf 'STAGED_COMMIT=$target\n'
"@
    $result=Invoke-AutoWonderCloudCommand -ManifestData $data -InstanceId $instanceId -Script $script
    if ($result.output -notmatch "STAGED_COMMIT=$target") { throw 'Remote staged release identity mismatch' }
    $nodes += [pscustomobject]@{instanceId=$instanceId;invocationId=$result.invocationId;status='verified'}
}
Update-JsonFileAtomic $Manifest {param($document)$document.upgrade.staging=[pscustomobject]@{status='verified';commit=$target;nodes=$nodes;verifiedAt=[DateTime]::UtcNow.ToString('o')};$document.phase='upgrade-stage';$document.status='staged';$document}
[pscustomobject]@{status='staged';commit=$target;nodeCount=$nodes.Count}|ConvertTo-Json -Compress

[CmdletBinding()]
param([Parameter(Mandatory)][string]$Manifest,[Parameter(Mandatory)][string]$SourceDirectory,[Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
$python = if ($env:AUTOWONDER_PYTHON) { $env:AUTOWONDER_PYTHON } else { 'python' }
. (Join-Path $PSScriptRoot 'windows-upgrade-common.ps1')
Protect-CurrentUserFile -Path $Manifest
$resolvedSource=@(& $python -B (Join-Path $PSScriptRoot 'upgrade_plan.py') resolve-source --source-dir $SourceDirectory)
if ($LASTEXITCODE -ne 0 -or $resolvedSource.Count -ne 1) { throw 'Target AutoWonder project directory is unavailable' }
$SourceDirectory=[string]$resolvedSource[0]
$data=Refresh-ApprovedUpgradeTargets -Manifest $Manifest
$target=[string]$data.upgrade.toCommit
$planFingerprint=[string]$data.upgrade.planFingerprint
if ($target -notmatch '^[0-9a-f]{40}$') { throw 'Build target must be an exact commit' }
$workspaceContent=($data.upgrade['sourceMode'] -eq 'workspace-current-content')
if ($workspaceContent) {
    $sourceCommit=(& $python -B (Join-Path $PSScriptRoot 'upgrade_plan.py') content-identity --source-dir $SourceDirectory).Trim()
    if ($LASTEXITCODE -ne 0 -or $sourceCommit -ne $target) { throw 'Workspace content differs from the approved release identity' }
} else {
    $sourceCommit=(& git -C $SourceDirectory rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $sourceCommit -ne $target) { throw 'Build source is not the exact target commit' }
    $dirty=@(& git -C $SourceDirectory status --porcelain --untracked-files=normal)
    if ($LASTEXITCODE -ne 0 -or $dirty.Count -gt 0) { throw 'Build requires an isolated clean target worktree' }
}
$unit=Join-Path $SourceDirectory 'skills\upgrading-autowonder-on-alibaba-cloud\assets\systemd\autowonder.service'
if (-not (Test-Path -LiteralPath $unit -PathType Leaf)) { throw 'Target upgrade Skill systemd unit is missing' }
$core=Join-Path $PSScriptRoot 'release_build.py'
$previousPreference=$ErrorActionPreference
try {
    # The core sends Maven progress to stderr and reserves stdout for JSON.
    # PowerShell 5.1 must not treat successful native stderr as a terminating error.
    $ErrorActionPreference='Continue'
    $buildOutput=@(& $python -B $core --source-dir $SourceDirectory --output-dir $OutputDirectory --include-unit)
    $buildExitCode=$LASTEXITCODE
} finally { $ErrorActionPreference=$previousPreference }
if ($buildExitCode -ne 0) { throw 'Shared release build failed' }
$build=ConvertTo-Hashtable (($buildOutput -join [Environment]::NewLine) | ConvertFrom-Json)
$OutputDirectory=[string]$build.directory
$artifacts=$build.artifacts
$data=Refresh-ApprovedUpgradeTargets -Manifest $Manifest
if ($data.upgrade.toCommit -ne $target -or $data.upgrade.planFingerprint -ne $planFingerprint) { throw 'Approved target changed during build' }
if ($workspaceContent) {
    $afterBuild=(& $python -B (Join-Path $PSScriptRoot 'upgrade_plan.py') content-identity --source-dir $SourceDirectory).Trim()
    if ($LASTEXITCODE -ne 0 -or $afterBuild -ne $target) { throw 'Workspace source changed during build' }
} else {
    $afterBuild=(& git -C $SourceDirectory rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $afterBuild -ne $target) { throw 'Target Git commit changed during build' }
    $dirty=@(& git -C $SourceDirectory status --porcelain --untracked-files=normal)
    if ($LASTEXITCODE -ne 0 -or $dirty.Count -gt 0) { throw 'Target worktree changed during build' }
}
$previousOutputEncoding=$OutputEncoding
try {
    $OutputEncoding=New-Object System.Text.UTF8Encoding($false)
    $releaseFieldsJson=($build | ConvertTo-Json -Depth 50 -Compress) | & $python -B (Join-Path $PSScriptRoot 'windows_release.py') --source-dir $SourceDirectory
    $releaseFieldsExitCode=$LASTEXITCODE
} finally { $OutputEncoding=$previousOutputEncoding }
if ($releaseFieldsExitCode -ne 0) { throw 'Release metadata generation failed' }
$releaseFields=ConvertTo-Hashtable (($releaseFieldsJson -join [Environment]::NewLine) | ConvertFrom-Json)
Update-JsonFileAtomic $Manifest {param($document)
    $document.upgrade.release=@{commit=$target;releaseDirectory=$OutputDirectory;planFingerprint=$document.upgrade.planFingerprint;artifacts=$artifacts;builtAt=[DateTime]::UtcNow.ToString('o')}
    $document.repositoryCommit=$target
    $document.source=@{kind='git';releaseId=$target;gitValidation='required'}
    if ($workspaceContent) { $document.source=@{kind='workspace';releaseId=$target;gitValidation='disabled';contentIdentity='sha256-file-set'} }
    $document.releaseVersion=$releaseFields.releaseVersion
    $document.artifacts=$releaseFields.artifacts
    $document.phase='upgrade-build';$document.status='built';$document
}
& $python -B (Join-Path $PSScriptRoot 'upgrade_plan.py') seal --manifest $Manifest --source-dir $SourceDirectory
if ($LASTEXITCODE -ne 0) { throw 'Target release baseline sealing failed' }
@{status='built';commit=$target;artifactCount=$artifacts.Count}|ConvertTo-Json -Compress

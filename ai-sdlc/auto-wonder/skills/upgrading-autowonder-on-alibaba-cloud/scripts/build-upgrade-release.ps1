[CmdletBinding()]
param([Parameter(Mandatory)][string]$Manifest,[Parameter(Mandatory)][string]$SourceDirectory,[Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '..\..\deploying-autowonder-on-alibaba-cloud\scripts\windows\lib.ps1')
$data=Get-ManifestData $Manifest; Assert-ApprovedUpgradePlan $data; Assert-VerifiedUpgradeTargets $data
$target=[string]$data.upgrade.toCommit
$sourceCommit=(& git -C $SourceDirectory rev-parse HEAD).Trim()
if ($sourceCommit -ne $target) { throw 'Build source is not the exact target commit' }
New-Item -ItemType Directory -Force -Path $OutputDirectory|Out-Null
# The repository's shell-contract tests are executed separately under their native
# harness; the Windows release build compiles and packages the exact source.
& mvn -f (Join-Path $SourceDirectory 'pom.xml') -B clean package '-DskipFrontend=false' '-DskipTests' '-DskipGitCommitId=true'
if ($LASTEXITCODE -ne 0) { throw 'Native Windows release build failed' }
$jar=Get-ChildItem (Join-Path $SourceDirectory 'target') -Filter '*.jar' -File|Where-Object Name -NotMatch 'sources|javadoc|original'|Sort-Object Length -Descending|Select-Object -First 1
if (-not $jar) { throw 'Built JAR is missing' }
Copy-Item $jar.FullName (Join-Path $OutputDirectory 'auto-wonder.jar') -Force
Copy-Item (Join-Path $SourceDirectory 'docs\autowonder-schema.sql') (Join-Path $OutputDirectory 'autowonder-schema.sql') -Force
Copy-Item (Join-Path $SourceDirectory 'docs\autowonder-community-templates.sql') (Join-Path $OutputDirectory 'autowonder-community-templates.sql') -Force
& tar -czf (Join-Path $OutputDirectory 'autowonder-migrations.tar.gz') -C (Join-Path $SourceDirectory 'docs') migration
if ($LASTEXITCODE -ne 0) { throw 'Migration archive build failed' }
$artifacts=[ordered]@{}
foreach($name in @('auto-wonder.jar','autowonder-schema.sql','autowonder-community-templates.sql','autowonder-migrations.tar.gz')){$path=Join-Path $OutputDirectory $name;$artifacts[$name]=[pscustomobject]@{sha256=Get-FileSha256 $path;size=(Get-Item $path).Length}}
Update-JsonFileAtomic $Manifest {param($document)$document.upgrade.release=[pscustomobject]@{commit=$target;directory=$OutputDirectory;artifacts=$artifacts;builtAt=[DateTime]::UtcNow.ToString('o')};$document.phase='upgrade-build';$document.status='built';$document}
[pscustomobject]@{status='built';commit=$target;artifactCount=$artifacts.Count}|ConvertTo-Json -Compress

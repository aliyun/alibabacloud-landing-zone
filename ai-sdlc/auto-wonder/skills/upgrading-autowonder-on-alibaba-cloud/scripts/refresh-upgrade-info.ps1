[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$Manifest
)

$ErrorActionPreference = 'Stop'
$DeploySkill = Join-Path $PSScriptRoot '..\..\deploying-autowonder-on-alibaba-cloud'
. (Join-Path $DeploySkill 'scripts\windows\lib.ps1')
$manifestData = Get-Content -LiteralPath $Manifest -Raw | ConvertFrom-Json
$profile = if ($manifestData.cloudProfile) { [string]$manifestData.cloudProfile } else { 'default' }
$region = [string]$manifestData.region
Import-AliyunCredential -Profile $profile -Region $region
Assert-AliyunIdentity -Profile $profile | Out-Null
$protectedEnv = [string]$manifestData.localContext.protectedEnvFile
if ($protectedEnv -and [IO.Path]::IsPathRooted($protectedEnv)) {
    $terraformConfig = Join-Path (Split-Path -Parent $protectedEnv) 'terraform.rc'
    if (Test-Path -LiteralPath $terraformConfig) { $env:TF_CLI_CONFIG_FILE = $terraformConfig }
}
$core = Join-Path $PSScriptRoot 'upgrade_info.py'
$output = (& python $core refresh --project-root $ProjectRoot --manifest $Manifest) -join [Environment]::NewLine
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$result = $output | ConvertFrom-Json
$infoDirectory = Split-Path -Parent ([string]$result.manifest)
Get-ChildItem -LiteralPath $infoDirectory -Filter '*.json' -File |
    ForEach-Object { Protect-CurrentUserFile -Path $_.FullName }
$output

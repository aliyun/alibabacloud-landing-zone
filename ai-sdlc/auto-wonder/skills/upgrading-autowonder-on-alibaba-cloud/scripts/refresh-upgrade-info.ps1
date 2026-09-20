[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$Manifest
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'windows\lib.ps1')
$manifestData = Get-Content -LiteralPath $Manifest -Raw | ConvertFrom-Json
$profile = 'auto-wonder'
$region = [string]$manifestData.region
Ensure-AutoWonderAliyunProfile -Region $region | Out-Null
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

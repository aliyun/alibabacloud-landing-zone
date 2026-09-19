#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$Profile = 'auto-wonder',
    [string]$Region = 'cn-beijing',
    [string]$ExpectedAccountId,
    [string]$Manifest
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')
if ($Profile -ne 'auto-wonder') { throw 'Only the auto-wonder Alibaba Cloud CLI profile is allowed' }
if ($Manifest) {
    $manifestData = Read-JsonHashtable -Path $Manifest
    if (Get-ObjectField $manifestData 'region') { $Region = [string]$manifestData.region }
    if (-not $ExpectedAccountId -and (Get-ObjectField $manifestData 'accountUid')) { $ExpectedAccountId = [string]$manifestData.accountUid }
}

# Python/JDK/Maven/Terraform/aliyun/ossutil/jq are resolved into a private
# process environment; never install global packages or alter the machine PATH.
$env:AUTOWONDER_PYTHON = & (Join-Path $PSScriptRoot 'runtime-bootstrap.ps1')
if ($LASTEXITCODE -ne 0 -or -not $env:AUTOWONDER_PYTHON) { throw 'Private Python bootstrap failed' }
$env:PYTHONUTF8 = '1'
$env:PYTHONDONTWRITEBYTECODE = '1'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\..\..')).Path
$runtimeText = & $env:AUTOWONDER_PYTHON (Join-Path $PSScriptRoot '..\tool_runtime.py') --emit-env --project-root $projectRoot
if ($LASTEXITCODE -ne 0) { throw 'Private runtime initialization failed' }
$runtime = $runtimeText | ConvertFrom-Json
foreach ($name in @('AUTOWONDER_PYTHON', 'JAVA_HOME', 'PATH')) {
    [Environment]::SetEnvironmentVariable($name, [string]$runtime.env.$name, 'Process')
}
foreach ($tool in @('git', 'curl.exe', 'tar')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "Required OS prerequisite is unavailable: $tool" }
}

$terraformCliConfig = & (Join-Path $PSScriptRoot 'configure-terraform-acceleration.ps1')

$identity = Ensure-AutoWonderAliyunProfile -Region $Region -ExpectedAccountId $ExpectedAccountId

[pscustomobject]@{
    platform = 'windows'
    profile = $Profile
    region = $Region
    accountId = [string]$identity.AccountId
    terraformCliConfigFile = [string]$terraformCliConfig
    sessionEnvironmentFile = $null
    runtimeEnvironment = $runtime.env
    validated = $true
} | ConvertTo-Json -Compress

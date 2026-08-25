[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$Manifest)

$ErrorActionPreference = 'Stop'
$DeploySkill = Join-Path $PSScriptRoot '..\..\deploying-autowonder-on-alibaba-cloud'
. (Join-Path $DeploySkill 'scripts\windows\lib.ps1')

$data = Get-Content -LiteralPath $Manifest -Raw | ConvertFrom-Json
function Assert-NoSecretField($Value) {
    if ($null -eq $Value -or $Value -is [string] -or $Value -is [ValueType]) { return }
    if ($Value -is [System.Collections.IEnumerable] -and $Value -isnot [pscustomobject]) {
        foreach ($child in $Value) { Assert-NoSecretField $child }
        return
    }
    foreach ($property in $Value.PSObject.Properties) {
        $normalized = ($property.Name.ToLowerInvariant() -replace '[-_.]', '')
        if ($normalized -match '^(?:password|secret|accesskey|accesskeyid|accesskeysecret|masterkey|jwtsecret|presignedurl|executortoken)$') {
            throw 'manifest contains a forbidden secret-bearing field'
        }
        Assert-NoSecretField $property.Value
    }
}
Assert-NoSecretField $data
$profile = if ($data.cloudProfile) { [string]$data.cloudProfile } else { 'default' }
$region = [string]$data.region
$deploymentId = [string]$data.deploymentId
if (-not $region -or -not $deploymentId) { throw 'Manifest region or deploymentId is missing' }
Import-AliyunCredential -Profile $profile -Region $region

$instanceObject = if ($data.resources.ecs_instance_ids) { $data.resources.ecs_instance_ids } else { $data.resources.ecsInstanceIds }
$instanceIds = @($instanceObject.PSObject.Properties | ForEach-Object { [string]$_.Value } | Sort-Object -Unique)
if ($instanceIds.Count -eq 0) { throw 'ECS inventory is empty' }
$verificationMode = if ($data.upgradeInfo -and $data.upgradeInfo.PSObject.Properties['tagVerificationMode']) {
    [string]$data.upgradeInfo.tagVerificationMode
} else { 'strict' }
$expectedVpc = [string]$data.resources.vpc_id
$expectedTags = @{}
foreach ($property in $data.tags.PSObject.Properties) { $expectedTags[$property.Name] = [string]$property.Value }
$expectedTags['Project'] = 'AutoWonder'
$expectedTags['DeploymentId'] = $deploymentId
$expectedTags['ManagedBy'] = 'Terraform'
if (-not $expectedTags['Environment']) { throw 'Manifest Environment tag is missing' }
if (-not $expectedTags['Topology']) { throw 'Manifest Topology tag is missing' }

$verified = @()
foreach ($instanceId in $instanceIds) {
    $raw = Invoke-AliyunFlat -Product 'ecs' -Action 'DescribeInstances' -Profile $profile -Parameters @{
        RegionId = $region
        InstanceIds = ('["' + $instanceId + '"]')
    }
    $response = ($raw -join [Environment]::NewLine) | ConvertFrom-Json
    $instance = @($response.Instances.Instance)[0]
    if ($null -eq $instance -or [string]$instance.InstanceId -ne $instanceId) { throw 'ECS target identity mismatch' }
    $liveVpc = [string]$instance.VpcAttributes.VpcId
    if ($expectedVpc -and $liveVpc -ne $expectedVpc) { throw 'ECS target VPC mismatch' }
    $liveTags = @{}
    foreach ($tag in @($instance.Tags.Tag)) { $liveTags[[string]$tag.TagKey] = [string]$tag.TagValue }
    if ($verificationMode -ne 'identity-only') {
        foreach ($key in $expectedTags.Keys) {
            if ($liveTags[$key] -ne $expectedTags[$key]) { throw 'ECS target tags do not match the deployment manifest' }
        }
    }
    $verified += [pscustomobject]@{ instanceId = $instanceId; vpcId = $liveVpc }
}

$cloudInstanceIds = @($instanceIds)
if ($verificationMode -ne 'identity-only') {
    $cloudInstanceIds = @()
    $page = 1
    do {
        $raw = Invoke-AliyunFlat -Product 'ecs' -Action 'DescribeInstances' -Profile $profile -Parameters @{
            RegionId = $region
            PageNumber = $page
            PageSize = 100
            'Tag.1.Key' = 'Project'
            'Tag.1.Value' = 'AutoWonder'
            'Tag.2.Key' = 'DeploymentId'
            'Tag.2.Value' = $deploymentId
        }
        $response = ($raw -join [Environment]::NewLine) | ConvertFrom-Json
        $pageIds = @($response.Instances.Instance | ForEach-Object { [string]$_.InstanceId })
        $cloudInstanceIds = @($cloudInstanceIds + $pageIds | Sort-Object -Unique)
        $total = if ($response.TotalCount) { [int]$response.TotalCount } else { $cloudInstanceIds.Count }
        $page += 1
    } while ($cloudInstanceIds.Count -lt $total -and $pageIds.Count -eq 100)
    if (@(Compare-Object -ReferenceObject $instanceIds -DifferenceObject $cloudInstanceIds).Count -ne 0) {
        throw 'Alibaba Cloud contains an ECS node outside Terraform inventory or Terraform contains a missing ECS node'
    }
}

$orderedTags = [ordered]@{}
foreach ($key in @($expectedTags.Keys | Sort-Object)) { $orderedTags[$key] = $expectedTags[$key] }
$fingerprintMaterial = [ordered]@{
    deploymentId = $deploymentId
    manifestInstanceIds = @($instanceIds | Sort-Object)
    nodes = @($verified | Sort-Object instanceId)
    region = $region
    tags = $orderedTags
    vpcId = $expectedVpc
}
$canonical = ($fingerprintMaterial | ConvertTo-Json -Depth 20 -Compress) + "`n"
$sha = [System.Security.Cryptography.SHA256]::Create()
try {
    $fingerprint = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical)))).Replace('-', '').ToLowerInvariant()
} finally { $sha.Dispose() }
$resourceSetFingerprint = if ($data.upgradeInfo.resourceSetFingerprint) {
    [string]$data.upgradeInfo.resourceSetFingerprint
} else {
    $fingerprint
}
Update-JsonFileAtomic -Path $Manifest -Update {
    param($document)
    if ($null -eq $document.upgrade) { $document.upgrade = @{} }
    $checkpoint = [pscustomobject]@{
        status = 'verified'; fingerprint = $fingerprint; nodes = $verified
        resourceSetFingerprint = $resourceSetFingerprint
        terraformInstanceIds = $instanceIds; cloudInstanceIds = $cloudInstanceIds
        verifiedAt = [DateTime]::UtcNow.ToString('o'); verifiedEpoch = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    }
    $document.upgrade.targetVerification = $checkpoint
    return $document
}

[pscustomobject]@{
    status = 'verified'
    deploymentId = $deploymentId
    region = $region
    nodes = $verified
    fingerprint = $fingerprint
    resourceSetFingerprint = $resourceSetFingerprint
} | ConvertTo-Json -Depth 10 -Compress

Set-StrictMode -Version Latest

function Protect-CurrentUserFile {
    param([Parameter(Mandatory)][string]$Path)
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    $acl = New-Object System.Security.AccessControl.FileSecurity
    $acl.SetOwner([System.Security.Principal.NTAccount]$identity)
    $acl.SetAccessRuleProtection($true, $false)
    $rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
        $identity, 'FullControl', 'Allow'
    )
    $acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $resolved -AclObject $acl
}

function Write-AtomicJson {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)]$Value,
        [int]$Depth = 100
    )
    $directory = Split-Path -Parent $Path
    if (-not $directory) { $directory = (Get-Location).Path }
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $temporary = Join-Path $directory ('.' + [IO.Path]::GetFileName($Path) + '.' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $Value | ConvertTo-Json -Depth $Depth | Set-Content -LiteralPath $temporary -Encoding utf8NoBOM
        Protect-CurrentUserFile -Path $temporary
        Move-Item -LiteralPath $temporary -Destination $Path -Force
        Protect-CurrentUserFile -Path $Path
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
}

function Update-JsonFileAtomic {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][scriptblock]$Update
    )
    $document = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -AsHashtable
    $updated = & $Update $document
    Write-AtomicJson -Path $Path -Value $updated
}

function Get-AliyunProfile {
    param([Parameter(Mandatory)][string]$Profile)
    $configPath = Join-Path $env:USERPROFILE '.aliyun\config.json'
    if (-not (Test-Path -LiteralPath $configPath)) { throw "Alibaba Cloud CLI profile is unavailable" }
    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    $entry = @($config.profiles | Where-Object { $_.name -eq $Profile })[0]
    if (-not $entry) { throw "Alibaba Cloud CLI profile is unavailable" }
    return $entry
}

function Import-AliyunCredential {
    param([Parameter(Mandatory)][string]$Profile, [Parameter(Mandatory)][string]$Region)
    $entry = Get-AliyunProfile -Profile $Profile
    $env:ALICLOUD_ACCESS_KEY = [string]$entry.access_key_id
    $env:ALICLOUD_SECRET_KEY = [string]$entry.access_key_secret
    $env:ALICLOUD_SECURITY_TOKEN = [string]$entry.sts_token
    $env:ALICLOUD_REGION = $Region
    $env:ALIBABA_CLOUD_REGION_ID = $Region
    if (-not $env:ALICLOUD_ACCESS_KEY -or -not $env:ALICLOUD_SECRET_KEY) {
        throw "Alibaba Cloud CLI profile does not contain an active session"
    }
}

function Get-AliyunExecutable {
    $command = Get-Command aliyun -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $link = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Links\aliyun.exe'
    if (Test-Path -LiteralPath $link) { return $link }
    throw 'Alibaba Cloud CLI is not installed'
}

function Invoke-AliyunFlat {
    param(
        [Parameter(Mandatory)][string]$Product,
        [Parameter(Mandatory)][string]$Action,
        [Parameter(Mandatory)][string]$Profile,
        [hashtable]$Parameters = @{}
    )
    $arguments = @($Product, $Action, '--profile', $Profile)
    foreach ($key in @($Parameters.Keys | Sort-Object)) {
        $arguments += @("--$key", [string]$Parameters[$key])
    }
    $output = & (Get-AliyunExecutable) @arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Alibaba Cloud API request failed: $Product/$Action" }
    return @($output)
}

function Invoke-AliyunJson {
    param(
        [Parameter(Mandatory)][string]$Product,
        [Parameter(Mandatory)][string]$Action,
        [Parameter(Mandatory)][string]$Profile,
        [hashtable]$Parameters = @{}
    )
    return ((Invoke-AliyunFlat -Product $Product -Action $Action -Profile $Profile -Parameters $Parameters) -join [Environment]::NewLine) | ConvertFrom-Json
}

function Assert-AliyunIdentity {
    param([Parameter(Mandatory)][string]$Profile, [string]$ExpectedAccountId)
    $identity = Invoke-AliyunJson -Product 'sts' -Action 'GetCallerIdentity' -Profile $Profile
    if ($ExpectedAccountId -and [string]$identity.AccountId -ne $ExpectedAccountId) {
        throw 'Alibaba Cloud account identity does not match the deployment manifest'
    }
    return $identity
}

function Get-FileSha256 {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function New-PrivateTemporaryDirectory {
    $path = Join-Path ([IO.Path]::GetTempPath()) ('autowonder-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $path | Out-Null
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    $acl = New-Object System.Security.AccessControl.DirectorySecurity
    $acl.SetOwner([System.Security.Principal.NTAccount]$identity)
    $acl.SetAccessRuleProtection($true, $false)
    $rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
        $identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'
    )
    $acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $path -AclObject $acl
    return $path
}

function Get-ManifestData {
    param([Parameter(Mandatory)][string]$Manifest)
    return Get-Content -LiteralPath $Manifest -Raw | ConvertFrom-Json
}

function Assert-ApprovedUpgradePlan {
    param([Parameter(Mandatory)]$ManifestData)
    if (-not $ManifestData.upgrade -or $ManifestData.upgrade.planStatus -ne 'approved') {
        throw 'An approved upgrade plan is required'
    }
    if (-not $ManifestData.upgrade.planFingerprint -or
        $ManifestData.upgrade.approvedPlanFingerprint -ne $ManifestData.upgrade.planFingerprint) {
        throw 'Approved upgrade plan fingerprint mismatch'
    }
}

function Assert-VerifiedUpgradeTargets {
    param([Parameter(Mandatory)]$ManifestData)
    $checkpoint = $ManifestData.upgrade.targetVerification
    if (-not $checkpoint -or $checkpoint.status -ne 'verified') {
        throw 'Fresh verified upgrade targets are required'
    }
    if ($checkpoint.resourceSetFingerprint -ne $ManifestData.upgradeInfo.resourceSetFingerprint) {
        throw 'Verified resource-set fingerprint mismatch'
    }
}

function Get-ManifestInstanceIds {
    param([Parameter(Mandatory)]$ManifestData)
    $object = if ($ManifestData.resources.ecs_instance_ids) { $ManifestData.resources.ecs_instance_ids } else { $ManifestData.resources.ecsInstanceIds }
    return @($object.PSObject.Properties | ForEach-Object { [string]$_.Value } | Sort-Object -Unique)
}

function Assert-UpgradeBackupCoverage {
    param([Parameter(Mandatory)]$ManifestData)
    $expected = @(Get-ManifestInstanceIds $ManifestData)
    $covered = @($ManifestData.upgrade.backup.nodes | ForEach-Object { [string]$_.instanceId } | Sort-Object -Unique)
    if ($ManifestData.upgrade.backup.status -ne 'verified' -or @(Compare-Object $expected $covered).Count -ne 0) {
        throw 'Verified upgrade backup is required for every ECS node'
    }
}

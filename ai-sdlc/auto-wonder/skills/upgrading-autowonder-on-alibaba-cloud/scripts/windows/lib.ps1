#requires -Version 5.1
Set-StrictMode -Version Latest
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $OutputEncoding
$script:AutoWonderUpgradeScripts = Join-Path $PSScriptRoot '..'

$script:AutoWonderOperationsCli = Join-Path $PSScriptRoot '..\operations-store.py'

function Invoke-OperationsStore {
    param([string]$Path, [ValidateSet('checkpoint', 'assert-current')][string]$Action)
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $document = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $Path).Path) | ConvertFrom-Json
    if ($null -eq $document.PSObject.Properties['operationsStore']) { return }
    & python $script:AutoWonderOperationsCli $Action --manifest $Path | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Operations state synchronization failed; stop before further changes' }
}

function Get-ObjectField {
    param($Value, [Parameter(Mandatory)][string]$Name)
    if ($null -eq $Value) { return $null }
    if ($Value -is [System.Collections.IDictionary]) { return $Value[$Name] }
    $property = $Value.PSObject.Properties[$Name]
    if ($null -ne $property) { return $property.Value }
    return $null
}

function ConvertTo-Hashtable {
    param($Value)
    if ($null -eq $Value) { return $null }
    if ($Value -is [System.Collections.IDictionary]) {
        $result = @{}
        foreach ($key in $Value.Keys) { $result[$key] = ConvertTo-Hashtable $Value[$key] }
        return $result
    }
    if ($Value -is [pscustomobject]) {
        $result = @{}
        foreach ($property in $Value.PSObject.Properties) { $result[$property.Name] = ConvertTo-Hashtable $property.Value }
        return $result
    }
    if ($Value -is [System.Collections.IEnumerable] -and $Value -isnot [string]) {
        $result = @()
        foreach ($item in $Value) { $result += ,(ConvertTo-Hashtable $item) }
        return ,$result
    }
    return $Value
}

function Read-JsonHashtable {
    param([Parameter(Mandatory)][string]$Path)
    return ConvertTo-Hashtable ([IO.File]::ReadAllText((Resolve-Path -LiteralPath $Path).Path, [Text.Encoding]::UTF8) | ConvertFrom-Json)
}

function Write-Utf8File {
    param([Parameter(Mandatory)][string]$Path, [AllowEmptyString()][string]$Content)
    [IO.File]::WriteAllText($Path, $Content, (New-Object Text.UTF8Encoding($false)))
}

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
    Invoke-OperationsStore -Path $Path -Action assert-current
    $directory = Split-Path -Parent $Path
    if (-not $directory) { $directory = (Get-Location).Path }
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $temporary = Join-Path $directory ('.' + [IO.Path]::GetFileName($Path) + '.' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        New-Item -ItemType File -Path $temporary | Out-Null
        Protect-CurrentUserFile -Path $temporary
        Write-Utf8File -Path $temporary -Content ($Value | ConvertTo-Json -Depth $Depth)
        Protect-CurrentUserFile -Path $temporary
        Move-Item -LiteralPath $temporary -Destination $Path -Force
        Protect-CurrentUserFile -Path $Path
        Invoke-OperationsStore -Path $Path -Action checkpoint
        if (Get-ObjectField $Value 'operationsStore') {
            $stored = Read-JsonHashtable -Path $Path
            if ($Value -is [System.Collections.IDictionary]) { $Value['operationsStore'] = $stored['operationsStore'] }
            else { $Value.operationsStore = $stored['operationsStore'] }
        }
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
}

function Update-JsonFileAtomic {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][scriptblock]$Update
    )
    $document = Read-JsonHashtable -Path $Path
    $updated = & $Update $document
    Write-AtomicJson -Path $Path -Value $updated
}

function Get-AliyunProfile {
    param([Parameter(Mandatory)][string]$Profile)
    $configPath = Join-Path $env:USERPROFILE '.aliyun\config.json'
    if (-not (Test-Path -LiteralPath $configPath)) { $failure = [Exception]::new('Alibaba Cloud CLI profile is unavailable'); $failure.Data['CloudCategory'] = 'credential'; throw $failure }
    $config = Read-JsonHashtable -Path $configPath
    $entries = @($config['profiles'] | Where-Object { $_['name'] -eq $Profile })
    $entry = if ($entries.Count -gt 0) { $entries[0] } else { $null }
    if (-not $entry) { $failure = [Exception]::new('Alibaba Cloud CLI profile is unavailable'); $failure.Data['CloudCategory'] = 'credential'; throw $failure }
    return $entry
}

function Import-AliyunCredential {
    param([Parameter(Mandatory)][string]$Profile, [Parameter(Mandatory)][string]$Region)
    $entry = Get-AliyunProfile -Profile $Profile
    Get-ChildItem Env: | Where-Object {
        $_.Name -like 'ALICLOUD_*' -or $_.Name -like 'ALIBABA_CLOUD_ACCESS_KEY*' -or
        $_.Name -eq 'ALIBABA_CLOUD_SECURITY_TOKEN' -or $_.Name -eq 'ALIBABA_CLOUD_PROFILE'
    } | ForEach-Object {
        Remove-Item -LiteralPath "Env:$($_.Name)"
    }
    $env:ALICLOUD_ACCESS_KEY = [string]$entry['access_key_id']
    $env:ALICLOUD_SECRET_KEY = [string]$entry['access_key_secret']
    $env:ALICLOUD_SECURITY_TOKEN = [string]$entry['sts_token']
    $env:ALICLOUD_REGION = $Region
    $env:ALIBABA_CLOUD_REGION_ID = $Region
    if (-not $env:ALICLOUD_ACCESS_KEY -or -not $env:ALICLOUD_SECRET_KEY) {
        $failure = [Exception]::new('Alibaba Cloud CLI profile does not contain an active session'); $failure.Data['CloudCategory'] = 'credential'; throw $failure
    }
}

function Get-AliyunExecutable {
    $command = Get-Command aliyun -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $link = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Links\aliyun.exe'
    if (Test-Path -LiteralPath $link) { return $link }
    throw 'Alibaba Cloud CLI is not installed'
}

function ConvertTo-WindowsNativeArgument {
    param([AllowEmptyString()][string]$Value)
    # MS C runtime quoting: double backslashes before quotes and at a quoted argument's end.
    $result = New-Object Text.StringBuilder
    [void]$result.Append('"')
    $slashes = 0
    foreach ($character in $Value.ToCharArray()) {
        if ($character -eq '\') { $slashes += 1; continue }
        if ($character -eq '"') {
            [void]$result.Append(('\' * (2 * $slashes + 1)))
            [void]$result.Append('"')
        } else {
            [void]$result.Append(('\' * $slashes))
            [void]$result.Append($character)
        }
        $slashes = 0
    }
    [void]$result.Append(('\' * (2 * $slashes)))
    [void]$result.Append('"')
    return $result.ToString()
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
    $process = New-Object Diagnostics.Process
    try {
        $process.StartInfo.FileName = Get-AliyunExecutable
        $process.StartInfo.Arguments = (@($arguments | ForEach-Object { ConvertTo-WindowsNativeArgument ([string]$_) }) -join ' ')
        $process.StartInfo.UseShellExecute = $false
        $process.StartInfo.CreateNoWindow = $true
        $process.StartInfo.RedirectStandardOutput = $true
        $process.StartInfo.RedirectStandardError = $true
        $process.StartInfo.StandardOutputEncoding = [Text.Encoding]::UTF8
        $process.StartInfo.StandardErrorEncoding = [Text.Encoding]::UTF8
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $output = $stdout.GetAwaiter().GetResult()
        $errorOutput = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            $python = $env:AUTOWONDER_PYTHON
            if (-not $python) {
                $command = Get-Command python -ErrorAction SilentlyContinue
                if (-not $command) { $command = Get-Command python3 -ErrorAction Stop }
                $python = $command.Source
            }
            $diagnostic = @{stdout=$output; stderr=$errorOutput} | ConvertTo-Json -Compress
            $safe = ($diagnostic | & $python (Join-Path $PSScriptRoot '..\cloud_diagnostics.py') classify) | ConvertFrom-Json
            $details = "category=$($safe.category)"
            if ($safe.code) { $details += "; code=$($safe.code)" }
            if ($safe.requestId) { $details += "; requestId=$($safe.requestId)" }
            $failure = [Exception]::new("Alibaba Cloud API request failed: $details")
            $failure.Data['CloudCategory'] = [string]$safe.category
            throw $failure
        }
        return @($output.TrimEnd([char[]]"`r`n") -split '\r?\n')
    } catch {
        try { if (-not $process.HasExited) { $process.Kill() } } catch { }
        if ($_.Exception.Data['CloudCategory']) { throw }
        throw "Alibaba Cloud API request failed: $Product/$Action"
    } finally { $process.Dispose() }
}

function Invoke-AliyunJson {
    param(
        [Parameter(Mandatory)][string]$Product,
        [Parameter(Mandatory)][string]$Action,
        [Parameter(Mandatory)][string]$Profile,
        [hashtable]$Parameters = @{}
    )
    try {
        return ConvertTo-Hashtable (((Invoke-AliyunFlat -Product $Product -Action $Action -Profile $Profile -Parameters $Parameters) -join [Environment]::NewLine) | ConvertFrom-Json)
    } catch {
        if ($_.Exception.Data['CloudCategory']) { throw }
        throw "Alibaba Cloud API response failed: $Product/$Action"
    }
}

function Assert-AliyunIdentity {
    param([Parameter(Mandatory)][string]$Profile, [string]$ExpectedAccountId)
    $identity = Invoke-AliyunJson -Product 'sts' -Action 'GetCallerIdentity' -Profile $Profile
    if ($ExpectedAccountId -and [string]$identity.AccountId -ne $ExpectedAccountId) {
        throw 'Alibaba Cloud account identity does not match the deployment manifest'
    }
    return $identity
}

function Ensure-AutoWonderAliyunProfile {
    param(
        [Parameter(Mandatory)][string]$Region,
        [string]$ExpectedAccountId
    )
    $Profile = 'auto-wonder'
    try {
        Import-AliyunCredential -Profile $Profile -Region $Region
        return Assert-AliyunIdentity -Profile $Profile -ExpectedAccountId $ExpectedAccountId
    } catch {
        if ($_.Exception.Data['CloudCategory'] -ne 'credential') { throw }
        try {
            & (Get-AliyunExecutable) configure --profile $Profile --mode OAuth 2>$null
        } catch { throw 'Alibaba Cloud OAuth login failed' }
        if ($LASTEXITCODE -ne 0) { throw 'Alibaba Cloud OAuth login failed' }
        Import-AliyunCredential -Profile $Profile -Region $Region
        return Assert-AliyunIdentity -Profile $Profile -ExpectedAccountId $ExpectedAccountId
    }
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
    Invoke-OperationsStore -Path $Manifest -Action assert-current
    return Read-JsonHashtable -Path $Manifest
}

function Get-UpgradeFingerprint {
    param([Parameter(Mandatory)]$ManifestData, [ValidateSet('fingerprint','target-fingerprint')][string]$Kind = 'fingerprint')
    # The shared Python canonicalizer keeps POSIX and Windows approvals identical.
    $temporary = Join-Path ([IO.Path]::GetTempPath()) ('autowonder-fingerprint-' + [guid]::NewGuid().ToString('N') + '.json')
    try {
        Write-AtomicJson -Path $temporary -Value $ManifestData
        $python = Get-Command python -ErrorAction SilentlyContinue
        if (-not $python) { $python = Get-Command python3 -ErrorAction Stop }
        try { $result = & $python.Source -B (Join-Path $script:AutoWonderUpgradeScripts 'upgrade_plan.py') $Kind --manifest $temporary 2>&1 }
        catch { throw 'Upgrade fingerprint calculation failed' }
        if ($LASTEXITCODE -ne 0 -or [string]$result -notmatch '^[0-9a-f]{64}$') { throw 'Upgrade fingerprint calculation failed' }
        return [string]$result
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
}

function Assert-ApprovedUpgradePlan {
    param([Parameter(Mandatory)]$ManifestData)
    $upgrade = Get-ObjectField $ManifestData 'upgrade'
    $approval = Get-ObjectField $upgrade 'approval'
    if ((Get-ObjectField $approval 'status') -ne 'approved') { throw 'An approved upgrade plan is required' }
    $recorded = [string](Get-ObjectField $upgrade 'planFingerprint')
    $actual = Get-UpgradeFingerprint -ManifestData $ManifestData
    if ($recorded -notmatch '^[0-9a-f]{64}$' -or $recorded -ne $actual -or
        (Get-ObjectField $approval 'planFingerprint') -ne $actual) { throw 'Approved upgrade plan fingerprint mismatch' }
    if (@(Get-ObjectField $upgrade 'blockedReasons').Count -gt 0) { throw 'Blocked upgrade plan cannot be executed' }
}

function Assert-VerifiedUpgradeTargets {
    param([Parameter(Mandatory)]$ManifestData)
    $checkpoint = Get-ObjectField (Get-ObjectField $ManifestData 'upgrade') 'targetVerification'
    if ((Get-ObjectField $checkpoint 'status') -ne 'verified') { throw 'Fresh verified upgrade targets are required' }
    $epoch = Get-ObjectField $checkpoint 'verifiedEpoch'
    $now = ([DateTime]::UtcNow - [DateTime]::SpecifyKind([DateTime]'1970-01-01', [DateTimeKind]::Utc)).TotalSeconds
    $parsedEpoch = 0.0
    if ($null -eq $epoch -or -not [double]::TryParse([string]$epoch, [ref]$parsedEpoch) -or
        [double]::IsNaN($parsedEpoch) -or [double]::IsInfinity($parsedEpoch) -or
        $now - $parsedEpoch -lt 0 -or $now - $parsedEpoch -gt 1800) { throw 'Live target verification expired' }
    $expected = @(Get-ManifestInstanceIds $ManifestData)
    if ($expected.Count -eq 0) { throw 'ECS inventory is empty' }
    $nodes = @(Get-ObjectField $checkpoint 'nodes')
    $verified = @($nodes | ForEach-Object { [string](Get-ObjectField $_ 'instanceId') } | Sort-Object -Unique)
    foreach ($observed in @(@{ids=$verified}, @{ids=@(Get-ObjectField $checkpoint 'terraformInstanceIds')}, @{ids=@(Get-ObjectField $checkpoint 'cloudInstanceIds')})) {
        if ($observed.ids.Count -eq 0 -or @(Compare-Object $expected @($observed.ids | Sort-Object -Unique)).Count -ne 0) { throw 'Verified ECS inventory mismatch' }
    }
    if ([string](Get-ObjectField $checkpoint 'fingerprint') -ne (Get-UpgradeFingerprint -ManifestData $ManifestData -Kind target-fingerprint)) {
        throw 'Live target verification fingerprint is stale'
    }
    $resourceSet = Get-ObjectField (Get-ObjectField $ManifestData 'upgradeInfo') 'resourceSetFingerprint'
    if ($resourceSet -and (Get-ObjectField $checkpoint 'resourceSetFingerprint') -ne $resourceSet) { throw 'Verified resource-set fingerprint mismatch' }
}

function Refresh-ApprovedUpgradeTargets {
    param([Parameter(Mandatory)][string]$Manifest)
    $data = Get-ManifestData -Manifest $Manifest
    Assert-ApprovedUpgradePlan -ManifestData $data
    & (Join-Path $script:AutoWonderUpgradeScripts 'verify-deployment-targets.ps1') -Manifest $Manifest | Out-Null
    $data = Get-ManifestData -Manifest $Manifest
    Assert-VerifiedUpgradeTargets -ManifestData $data
    Assert-ApprovedUpgradePlan -ManifestData $data
    return $data
}

function Get-ManifestInstanceIds {
    param([Parameter(Mandatory)]$ManifestData)
    $resources = Get-ObjectField $ManifestData 'resources'
    $inventory = Get-ObjectField $resources 'ecs_instance_ids'
    if ($null -eq $inventory) { $inventory = Get-ObjectField $resources 'ecsInstanceIds' }
    if ($inventory -is [System.Collections.IDictionary]) { $values = @($inventory.Values) }
    elseif ($inventory -is [pscustomobject]) { $values = @($inventory.PSObject.Properties | ForEach-Object { $_.Value }) }
    else { $values = @($inventory) }
    return @($values | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | ForEach-Object { [string]$_ } | Sort-Object -Unique)
}

function Assert-UpgradeBackupCoverage {
    param([Parameter(Mandatory)]$ManifestData)
    $expected = @(Get-ManifestInstanceIds $ManifestData)
    $upgrade = Get-ObjectField $ManifestData 'upgrade'
    $backup = Get-ObjectField $upgrade 'backup'
    if ((Get-ObjectField $backup 'planFingerprint') -ne (Get-ObjectField $upgrade 'planFingerprint') -or
        (Get-ObjectField $backup 'fromCommit') -ne (Get-ObjectField $upgrade 'fromCommit') -or
        (Get-ObjectField $backup 'targetCommit') -ne (Get-ObjectField $upgrade 'toCommit')) {
        throw 'Upgrade backup does not belong to the current approved plan'
    }
    $nodes = @(Get-ObjectField $backup 'nodes')
    foreach ($node in $nodes) {
        if ((Get-ObjectField $node 'status') -ne 'verified' -or
            [string](Get-ObjectField $node 'sha256') -notmatch '^[0-9a-f]{64}$') {
            throw 'Every upgrade backup node requires a verified archive checksum'
        }
    }
    $covered = @($nodes | ForEach-Object { [string](Get-ObjectField $_ 'instanceId') } | Sort-Object -Unique)
    if ((Get-ObjectField $backup 'status') -ne 'verified' -or $expected.Count -eq 0 -or $covered.Count -eq 0 -or
        @(Compare-Object $expected $covered).Count -ne 0) { throw 'Verified upgrade backup is required for every ECS node' }
}

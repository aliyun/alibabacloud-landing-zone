. (Join-Path $PSScriptRoot 'windows\lib.ps1')
. (Join-Path $PSScriptRoot 'windows\cloud-assistant.ps1')

function New-UpgradeRemoteRequest {
    param($Data, [string]$Operation)
    return @{operation=$Operation;plan=[string]$Data.upgrade.planFingerprint;target=[string]$Data.upgrade.toCommit;from=[string]$Data.upgrade.fromCommit}
}

function Invoke-UpgradePayload {
    param($Data, [string]$InstanceId, [hashtable]$Request, [Parameter(Mandatory)][string]$ManifestPath)
    $json = $Request | ConvertTo-Json -Depth 40 -Compress
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json))
    $payload = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'remote\upgrade_remote.py')).Replace('@@REQUEST@@', $encoded)
    $buffer=New-Object IO.MemoryStream
    try {
        $compressor=[IO.Compression.GZipStream]::new($buffer,[IO.Compression.CompressionMode]::Compress,$true)
        try { $bytes=[Text.Encoding]::UTF8.GetBytes($payload);$compressor.Write($bytes,0,$bytes.Length) } finally { $compressor.Dispose() }
        $packed=[Convert]::ToBase64String($buffer.ToArray())
    } finally { $buffer.Dispose() }
    $script='set -o pipefail; printf %s ' + $packed + ' | base64 -d | gzip -d | python3'
    if ($script.Length -gt 24000) { throw 'Compressed Cloud Assistant payload exceeds the native command size limit' }
    return Invoke-AutoWonderCloudCommand -ManifestData $Data -InstanceId $InstanceId -Script $script -ManifestPath $ManifestPath -Operation ([string]$Request.operation)
}

function Get-UpgradeResultValue {
    param($Result, [string]$Name, [string]$Pattern)
    $match = [regex]::Match([string]$Result.output, ('(?m)^' + $Name + '=('+ $Pattern + ')\r?$'))
    if (-not $match.Success) { throw "Remote upgrade evidence missing: $Name" }
    return $match.Groups[1].Value
}

function Assert-UpgradeCandidate {
    param($Data, [string]$EnvFile)
    if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) { throw 'Protected candidate environment file is required' }
    if ((Get-FileSha256 $EnvFile) -ne [string]$Data.upgrade.environmentSha256) { throw 'Candidate environment hash differs from approved plan' }
    $version=[string]$Data.upgrade.targetRecommendedRuntimeVersion
    if ($version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$') { throw 'Target runtime recommended version is missing' }
    $values=@{}
    foreach ($line in [IO.File]::ReadAllLines($EnvFile)) {
        if (-not $line.Trim() -or $line.TrimStart().StartsWith('#')) { continue }
        if ($line -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') { throw 'Invalid candidate environment syntax' }
        $key=$Matches[1]; $value=$Matches[2]
        if ($values.ContainsKey($key)) { throw 'Duplicate candidate environment key' }
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value=$value.Substring(1,$value.Length-2) }
        $values[$key]=$value
    }
    $activeEnv=[string]$Data.localContext['activeEnvFile']
    if (-not $activeEnv) { $activeEnv=[string]$Data.localContext['protectedEnvFile'] }
    $python=Get-Command python -ErrorAction SilentlyContinue
    if (-not $python) { $python=Get-Command python3 -ErrorAction Stop }
    & $python.Source -B (Join-Path $PSScriptRoot 'upgrade_plan.py') check-candidate --original-env-file $activeEnv --env-file $EnvFile
    if ($LASTEXITCODE -ne 0) { throw 'Candidate master key continuity check failed' }
    if ($values['AUTOWONDER_RUNTIME_RECOMMENDED_VERSION'] -ne $version) { throw 'Candidate runtime recommended version differs from target source' }
    $required=@('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','REDIS_HOST','AUTOWONDER_SECRET_MASTER_KEY','AUTOWONDER_JWT_SECRET')
    if ($Data.upgrade['environment']) { $required += @($Data.upgrade.environment['required']) }
    foreach ($key in $required) { if (-not $values[$key]) { throw "Required environment key missing: $key" } }
    if ($values['AUTOWONDER_SECRET_MASTER_KEY'] -cnotmatch '^[A-Za-z0-9+/]{43}=$' -or [Convert]::FromBase64String($values['AUTOWONDER_SECRET_MASTER_KEY']).Length -ne 32) { throw 'Master key must be strict single-line Base64 encoding 32 bytes' }
}


function Assert-UpgradeStaging {
    param($Data)
    Assert-UpgradeBackupCoverage $Data
    $stage=$Data.upgrade['staging']
    if (-not $stage -or $stage.status -ne 'verified' -or $stage.commit -ne $Data.upgrade.toCommit -or $stage.planFingerprint -ne $Data.upgrade.planFingerprint) { throw 'Verified staging for the approved plan is required' }
    $expected=@(Get-ManifestInstanceIds $Data)
    $covered=@($stage.nodes | ForEach-Object { [string]$_.instanceId } | Sort-Object -Unique)
    if (@(Compare-Object $expected $covered).Count -ne 0) { throw 'Staging does not cover every verified ECS' }
    $runtime=$Data['runtimeConfig']
    if (-not $runtime -or -not $runtime.prepared -or $runtime.recommendedRuntimeVersion -ne $Data.upgrade.targetRecommendedRuntimeVersion -or $runtime.candidateSha256 -ne $Data.upgrade.environmentSha256) { throw 'Target runtime environment checkpoint is incomplete or stale' }
    if ($runtime.planFingerprint -ne $Data.upgrade.planFingerprint) { throw 'Protected environment checkpoint is incomplete or stale' }
}

function Get-UpgradeBackupSha {
    param($Data, [string]$InstanceId)
    $entry=@($Data.upgrade.backup.nodes | Where-Object { $_.instanceId -eq $InstanceId })
    if ($entry.Count -ne 1 -or $entry[0].sha256 -notmatch '^[0-9a-f]{64}$') { throw 'Verified ECS backup checksum is missing' }
    return [string]$entry[0].sha256
}

function New-UpgradeOssSession {
    param($Data)
    $directory=New-PrivateTemporaryDirectory
    try {
        Import-AliyunCredential -Profile 'auto-wonder' -Region ([string]$Data.region)
        $config=Join-Path $directory 'ossutil.ini'
        $lines=@('[Credentials]',"accessKeyID=$env:ALICLOUD_ACCESS_KEY","accessKeySecret=$env:ALICLOUD_SECRET_KEY")
        if ($env:ALICLOUD_SECURITY_TOKEN) { $lines += "stsToken=$env:ALICLOUD_SECURITY_TOKEN" }
        [IO.File]::WriteAllLines($config,$lines,(New-Object Text.UTF8Encoding($false)))
        Protect-CurrentUserFile $config
        # Unsupported help commands write stderr on legacy ossutil; this is an
        # expected capability probe, including under Windows PowerShell 5.1.
        $previousPreference=$ErrorActionPreference
        try {
            $ErrorActionPreference='Continue'
            $help=@(& ossutil help presign 2>&1) -join [Environment]::NewLine
            $v2=($LASTEXITCODE -eq 0 -and $help -match '--expires-duration')
            if (-not $v2) {
                $help=@(& ossutil help sign 2>&1) -join [Environment]::NewLine
                if ($LASTEXITCODE -ne 0 -or $help -notmatch '--timeout') { throw 'Installed ossutil has no supported signing contract' }
            }
        } finally { $ErrorActionPreference=$previousPreference }
        return @{directory=$directory;config=$config;v2=$v2;region=[string]$Data.region}
    } catch { Remove-Item -LiteralPath $directory -Recurse -Force; throw }
}

function Invoke-UpgradeOss {
    param($Session, [string]$Operation, [string]$Object, [string]$Endpoint, [string]$Source)
    $arguments=@()
    if (-not $Session.v2) { $arguments+=@('-c',$Session.config) }
    switch ($Operation) {
        'upload' { $arguments+=@('cp','-f',$Source,$Object) }
        'remove' { $arguments+=@('rm','-f',$Object) }
        'sign' {
            if ($Session.v2) { $arguments+=@('presign',$Object,'--expires-duration','15m') }
            else { $arguments+=@('sign',$Object,'--timeout','900') }
        }
        default { throw 'Unsupported OSS upgrade operation' }
    }
    $arguments+=@('-e',$Endpoint)
    if ($Session.v2) { $arguments+=@('--region',$Session.region) }
    $saved=@{}
    if ($Session.v2) {
        foreach ($name in @('OSS_ACCESS_KEY_ID','OSS_ACCESS_KEY_SECRET','OSS_SESSION_TOKEN')) { $saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process') }
        $env:OSS_ACCESS_KEY_ID=$env:ALICLOUD_ACCESS_KEY;$env:OSS_ACCESS_KEY_SECRET=$env:ALICLOUD_SECRET_KEY;$env:OSS_SESSION_TOKEN=$env:ALICLOUD_SECURITY_TOKEN
    }
    try {
        $output=@(& ossutil @arguments 2>&1)
        if ($LASTEXITCODE -ne 0) { throw "Private OSS upgrade operation failed: $Operation" }
    } finally { foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') } }
    if ($Operation -eq 'sign') {
        $match=[regex]::Match(($output -join [Environment]::NewLine),'https?://[^\s]+')
        if (-not $match.Success -or $match.Value -notmatch '\?') { throw 'Invalid private OSS staging URL' }
        return $match.Value
    }
}

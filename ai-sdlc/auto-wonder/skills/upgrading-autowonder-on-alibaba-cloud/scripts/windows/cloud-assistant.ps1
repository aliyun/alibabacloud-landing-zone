. (Join-Path $PSScriptRoot 'lib.ps1')

$script:AutoWonderCloudAssistantCore = Join-Path $PSScriptRoot '..\cloud_assistant.py'

function Invoke-AutoWonderCloudCore {
    param([string]$Action, $Document)
    $python = $env:AUTOWONDER_PYTHON
    if (-not $python) {
        $command = Get-Command python -ErrorAction SilentlyContinue
        if (-not $command) { $command = Get-Command python3 -ErrorAction Stop }
        $python = $command.Source
    }
    # UTF-8 input and ASCII JSON output also work on Windows PowerShell 5.1.
    $OutputEncoding = New-Object Text.UTF8Encoding($false)
    try { $result = ($Document | ConvertTo-Json -Depth 100 -Compress) | & $python -B $script:AutoWonderCloudAssistantCore $Action 2>&1 }
    catch { throw 'Cloud Assistant response or recovery state is invalid' }
    if ($LASTEXITCODE -ne 0) {
        if ($Action -eq 'assert-settled') { throw 'Previous remote operation is unresolved; reconcile before retrying' }
        throw 'Cloud Assistant response is invalid'
    }
    if ($Action -eq 'poll') { return ([string]$result | ConvertFrom-Json) }
    if ($Action -ne 'assert-settled') { return [string]$result }
}

function Save-AutoWonderCloudInvocation {
    param([string]$ManifestPath, [string]$InvocationId, [string]$InstanceId, [string]$Operation, [string]$Status)
    if (-not $ManifestPath) { return }
    Update-JsonFileAtomic -Path $ManifestPath -Update {
        param($document)
        if (-not $document['upgrade']) { $document['upgrade'] = @{} }
        $entries = @(Get-ObjectField $document['upgrade'] 'remoteInvocations')
        $matched = @($entries | Where-Object { $_['invocationId'] -eq $InvocationId })
        if ($matched.Count -eq 0) {
            $entry = @{invocationId=$InvocationId;instanceId=$InstanceId;operation=$Operation;submittedAt=[DateTime]::UtcNow.ToString('o')}
            $entries += $entry
        } else { $entry = $matched[0] }
        $entry['status'] = $Status
        $entry['updatedAt'] = [DateTime]::UtcNow.ToString('o')
        $document['upgrade']['remoteInvocations'] = $entries
        $document['remoteSubmission'] = $null
        return $document
    }
}

function Invoke-AutoWonderCloudCommand {
    param(
        [Parameter(Mandatory)]$ManifestData,
        [Parameter(Mandatory)][string]$InstanceId,
        [Parameter(Mandatory)][string]$Script,
        [int]$TimeoutSeconds = 1800,
        [string]$ManifestPath,
        [string]$Operation = 'cloud-command'
    )
    $profile = 'auto-wonder'
    if ((Get-ObjectField $ManifestData 'cloudProfile') -and [string](Get-ObjectField $ManifestData 'cloudProfile') -ne $profile) {
        throw 'Only the auto-wonder Alibaba Cloud CLI profile is allowed'
    }
    $region = [string]$ManifestData.region
    Import-AliyunCredential -Profile $profile -Region $region
    if ($ManifestPath) {
        $current = Read-JsonHashtable -Path $ManifestPath
        Invoke-AutoWonderCloudCore -Action assert-settled -Document $current
        Update-JsonFileAtomic -Path $ManifestPath -Update {
            param($document)
            $document['remoteSubmission'] = @{instanceId=$InstanceId;operation=$Operation;status='unknown';preparedAt=[DateTime]::UtcNow.ToString('o')}
            return $document
        }
    } elseif (Get-ObjectField $ManifestData 'operationsStore') { throw 'Bound cloud operations require a manifest path' }
    $submitted = Invoke-AliyunJson -Product 'ecs' -Action 'RunCommand' -Profile $profile -Parameters @{
        RegionId = $region; 'InstanceId.1' = $InstanceId; Type = 'RunShellScript'
        Timeout = $TimeoutSeconds; CommandContent = $Script
    }
    $invokeId = Invoke-AutoWonderCloudCore -Action invocation-id -Document $submitted
    $record = @{ManifestPath=$ManifestPath;InvocationId=$invokeId;InstanceId=$InstanceId;Operation=$Operation}
    Save-AutoWonderCloudInvocation @record -Status submitted
    $state = 'submitted'
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds + 60)
    try {
        do {
            Start-Sleep -Seconds 2
            $response = Invoke-AliyunJson -Product 'ecs' -Action 'DescribeInvocationResults' -Profile $profile -Parameters @{
                RegionId = $region; InvokeId = $invokeId
            }
            $parsed = Invoke-AutoWonderCloudCore -Action poll -Document $response
            if ($parsed.state -eq 'success') {
                $state = 'finished'
                Save-AutoWonderCloudInvocation @record -Status $state
                return [pscustomobject]@{ invocationId = $invokeId; status = 'finished'; output = $parsed.output }
            }
            if ($parsed.state -eq 'failure') {
                $state = 'failed'
                Save-AutoWonderCloudInvocation @record -Status $state
                throw 'Cloud Assistant invocation reached terminal failure'
            }
        } while ([DateTime]::UtcNow -lt $deadline)
        $state = 'timed-out'
        Save-AutoWonderCloudInvocation @record -Status $state
        throw 'Cloud Assistant invocation timed out'
    } catch {
        if ($state -eq 'submitted') { Save-AutoWonderCloudInvocation @record -Status error }
        throw
    }
}

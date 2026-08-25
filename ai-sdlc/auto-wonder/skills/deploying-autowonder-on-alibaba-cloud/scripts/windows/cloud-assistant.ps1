. (Join-Path $PSScriptRoot 'lib.ps1')

function Invoke-AutoWonderCloudCommand {
    param(
        [Parameter(Mandatory)]$ManifestData,
        [Parameter(Mandatory)][string]$InstanceId,
        [Parameter(Mandatory)][string]$Script,
        [int]$TimeoutSeconds = 1800
    )
    $profile = if ($ManifestData.cloudProfile) { [string]$ManifestData.cloudProfile } else { 'default' }
    $region = [string]$ManifestData.region
    Import-AliyunCredential -Profile $profile -Region $region
    $submitted = Invoke-AliyunJson -Product 'ecs' -Action 'RunCommand' -Profile $profile -Parameters @{
        RegionId = $region; 'InstanceId.1' = $InstanceId; Type = 'RunShellScript'
        Timeout = $TimeoutSeconds; CommandContent = $Script
    }
    $invokeId = [string]$submitted.InvokeId
    if (-not $invokeId) { $invokeId = [string]$submitted.InvocationId }
    if (-not $invokeId) { throw 'Cloud Assistant invocation ID is missing' }
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds + 60)
    do {
        Start-Sleep -Seconds 2
        $response = Invoke-AliyunJson -Product 'ecs' -Action 'DescribeInvocationResults' -Profile $profile -Parameters @{
            RegionId = $region; InvokeId = $invokeId
        }
        $result = @($response.Invocation.InvocationResults.InvocationResult)[0]
        if (-not $result) { $result = @($response.InvocationResults.InvocationResult)[0] }
        $status = [string]$result.InvocationStatus
        if (-not $status) { $status = [string]$result.Status }
        if ($status -in @('Finished', 'Success')) {
            if ([int]$result.ExitCode -ne 0) { throw 'Cloud Assistant command failed' }
            $output = ''
            if ($result.Output) {
                try { $output = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String([string]$result.Output)) }
                catch { throw 'Cloud Assistant returned invalid encoded output' }
            }
            return [pscustomobject]@{ invocationId = $invokeId; status = 'finished'; output = $output.Trim() }
        }
        if ($status -in @('Failed','PartialFailed','Stopped','TimedOut','Cancelled','Invalid','Aborted','Terminated')) {
            throw 'Cloud Assistant invocation reached terminal failure'
        }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Cloud Assistant invocation timed out'
}

. (Join-Path $PSScriptRoot 'windows\lib.ps1')
. (Join-Path $PSScriptRoot 'windows\cloud-assistant.ps1')

function Invoke-UpgradePayload {
    param($Data, [string]$InstanceId, [hashtable]$Request, [Parameter(Mandatory)][string]$ManifestPath)
    if ($Request.operation -ne 'upgrade-inventory') { throw 'Only upgrade-inventory is supported by the deployment Skill' }
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

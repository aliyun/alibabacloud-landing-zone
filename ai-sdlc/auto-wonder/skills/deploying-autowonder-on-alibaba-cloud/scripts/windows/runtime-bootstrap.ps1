# Python/jq-free Windows PowerShell 5.1 and PowerShell 7 cold start.
[CmdletBinding()]
param([string]$ProjectRoot)
$ErrorActionPreference = 'Stop'
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $OutputEncoding
Set-StrictMode -Version Latest
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$canonicalRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
if (-not $ProjectRoot) { $ProjectRoot = $canonicalRoot }
$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).ProviderPath
if ($ProjectRoot.TrimEnd('\') -ne $canonicalRoot.TrimEnd('\') -or -not (Test-Path -LiteralPath (Join-Path $ProjectRoot 'pom.xml') -PathType Leaf)) {
    throw 'project root must match the canonical skill checkout'
}
$architecture = $env:PROCESSOR_ARCHITEW6432
if (-not $architecture) { $architecture = $env:PROCESSOR_ARCHITECTURE }
switch ($architecture.ToUpperInvariant()) {
    'AMD64' { $arch = 'x86_64' }
    'ARM64' { $arch = 'arm64' }
    default { throw 'unsupported Windows architecture' }
}
$aliases = if ($arch -eq 'arm64') { "('arm64', 'aarch64')" } else { "('x86_64', 'amd64')" }
$selfcheck = "import ssl,hashlib,zipfile,platform,struct,argparse,json,tarfile,urllib.request,subprocess,pathlib,ipaddress,shutil; assert platform.machine().lower() in $aliases; assert struct.calcsize('P') == 8; print(platform.python_version())"
$rows = @(Get-Content -LiteralPath (Join-Path $PSScriptRoot '../runtime-lock.tsv') | Where-Object { $_ -and -not $_.StartsWith('#') } | ForEach-Object {
    $parts = $_.Split("`t")
    if ($parts.Length -ne 8) { throw 'invalid runtime lock row' }
    if ($parts[0] -eq 'python' -and $parts[1] -eq 'windows' -and $parts[2] -eq $arch) { ,$parts }
})
if ($rows.Count -ne 1) { throw 'missing or duplicate locked Python asset for this platform' }
$row = $rows[0]
$version, $url, $digest, $format, $relative = $row[3..7]
if ($version -notmatch '^\d+\.\d+\.\d+$' -or $url -notmatch '^https://' -or $digest -cnotmatch '^[a-f0-9]{64}$' -or $relative -match '(^/|\.\.|:|\\)' -or -not $relative) {
    throw 'invalid Python runtime lock'
}
if ($format -ne 'tar.gz' -and $format -ne 'zip') { throw 'unsupported Python archive format' }
$toolsRoot = Join-Path $ProjectRoot 'skills/.autowonder-tools'
$parent = Join-Path $toolsRoot "python/$version"
$target = Join-Path $parent "windows-$arch"
foreach ($path in @($toolsRoot, (Join-Path $toolsRoot 'python'), $parent, $target)) {
    if ((Test-Path -LiteralPath $path) -and ((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'tool cache cannot contain reparse points' }
}
[void][IO.Directory]::CreateDirectory($parent)
$rootItem = Get-Item -LiteralPath $toolsRoot -Force
$rootItem.Attributes = $rootItem.Attributes -bor [IO.FileAttributes]::Hidden
# Private tools are executable code: only the current user inherits access.
$acl = Get-Acl -LiteralPath $toolsRoot
$acl.SetAccessRuleProtection($true, $false)
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
$rule = New-Object Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
$acl.AddAccessRule($rule)
Set-Acl -LiteralPath $toolsRoot -AclObject $acl
function Test-CachedPython {
    $binary = Join-Path $target $relative
    $marker = Join-Path $target '.verified'
    if (-not (Test-Path -LiteralPath $binary -PathType Leaf) -or -not (Test-Path -LiteralPath $marker -PathType Leaf)) { return $false }
    $expected = "$digest`n$((Get-FileHash -LiteralPath $binary -Algorithm SHA256).Hash.ToLowerInvariant())`n"
    if ([IO.File]::ReadAllText($marker).Replace("`r`n", "`n") -ne $expected) { return $false }
    try { $reported = & $binary -I -c $selfcheck 2>$null; return $LASTEXITCODE -eq 0 -and "$reported" -eq $version } catch { return $false }
}
if (Test-CachedPython) { Write-Output (Join-Path $target $relative); exit 0 }
$lock = "$target.lock"
try { [void](New-Item -ItemType Directory -Path $lock -ErrorAction Stop) } catch { throw 'tool installation locked; inspect interrupted installer before removing lock' }
$staging = $null
try {
    [IO.File]::WriteAllText((Join-Path $lock 'pid'), "$PID")
    if (Test-CachedPython) { Write-Output (Join-Path $target $relative); exit 0 }
    $staging = Join-Path $parent ('.install-' + [Guid]::NewGuid().ToString('N'))
    [void][IO.Directory]::CreateDirectory($staging)
    $asset = Join-Path $staging 'archive'
    $previousProtocol = [Net.ServicePointManager]::SecurityProtocol
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $asset -TimeoutSec 300
    } finally { [Net.ServicePointManager]::SecurityProtocol = $previousProtocol }
    if ((Get-FileHash -LiteralPath $asset -Algorithm SHA256).Hash.ToLowerInvariant() -ne $digest) { throw 'Python checksum mismatch' }
    $content = Join-Path $staging 'content'
    [void][IO.Directory]::CreateDirectory($content)
    if ($format -eq 'tar.gz') {
        $tar = (Get-Command tar.exe -CommandType Application -ErrorAction Stop).Source
        $entries = & $tar -tzf $asset
        if ($LASTEXITCODE -ne 0) { throw 'invalid Python archive' }
        foreach ($entry in $entries) { if ($entry -match '(^[/\\]|(^|[/\\])\.\.([/\\]|$)|:)') { throw 'unsafe Python archive path' } }
        & $tar -xzf $asset -C $content
        if ($LASTEXITCODE -ne 0) { throw 'Python archive extraction failed' }
    } else {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [IO.Compression.ZipFile]::OpenRead($asset)
        try { foreach ($entry in $zip.Entries) { if ($entry.FullName -match '(^[/\\]|(^|[/\\])\.\.([/\\]|$)|:)') { throw 'unsafe Python archive path' } } } finally { $zip.Dispose() }
        [IO.Compression.ZipFile]::ExtractToDirectory($asset, $content)
    }
    $binary = Join-Path $content $relative
    $reported = & $binary -I -c $selfcheck
    if ($LASTEXITCODE -ne 0 -or "$reported" -ne $version) { throw 'Python version/self-check failed' }
    [IO.File]::WriteAllText((Join-Path $content '.verified'), "$digest`n$((Get-FileHash -LiteralPath $binary -Algorithm SHA256).Hash.ToLowerInvariant())`n", [Text.Encoding]::ASCII)
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
    [IO.Directory]::Move($content, $target)
    Write-Output (Join-Path $target $relative)
} finally {
    if ($staging -and (Test-Path -LiteralPath $staging)) { Remove-Item -LiteralPath $staging -Recurse -Force }
    Remove-Item -LiteralPath $lock -Recurse -Force
}

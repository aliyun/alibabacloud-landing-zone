[CmdletBinding()]
param(
    [string]$Profile = 'default',
    [string]$Region = 'cn-beijing',
    [string]$ExpectedAccountId
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')

$requirements = [ordered]@{
    git = 'Git.Git'
    jq = 'jqlang.jq'
    terraform = 'Hashicorp.Terraform'
    aliyun = 'Alibaba.AlibabaCloudCLI'
    ossutil = 'Alibaba.ossutil'
    'curl.exe' = $null
    python = 'Python.Python.3.13'
    tar = 'GnuWin32.Tar'
    java = 'EclipseAdoptium.Temurin.21.JDK'
    mvn = 'Apache.Maven'
}

foreach ($tool in $requirements.Keys) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        if ($tool -eq 'java') {
            $java = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Recurse -Filter java.exe -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($java) { $env:PATH = "$(Split-Path -Parent $java.FullName);$env:PATH"; continue }
        }
        if ($tool -eq 'mvn') {
            $mavenBin = Join-Path $env:LOCALAPPDATA 'AutoWonderTools\apache-maven-3.9.9\bin'
            if (Test-Path -LiteralPath (Join-Path $mavenBin 'mvn.cmd')) { $env:PATH = "$mavenBin;$env:PATH"; continue }
        }
        if ($tool -eq 'ossutil') {
            $ossBin = Join-Path $env:LOCALAPPDATA 'Programs\ossutil'
            if (Test-Path -LiteralPath (Join-Path $ossBin 'ossutil.exe')) { $env:PATH = "$ossBin;$env:PATH"; continue }
        }
        $link = Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Links\$tool.exe"
        if (Test-Path -LiteralPath $link) { continue }
        if ($tool -eq 'ossutil') {
            $install = Join-Path $env:LOCALAPPDATA 'Programs\ossutil'
            $archive = Join-Path ([IO.Path]::GetTempPath()) 'ossutil-v1.7.19-windows-amd64.zip'
            try {
                Invoke-WebRequest -Uri 'https://gosspublic.alicdn.com/ossutil/1.7.19/ossutil-v1.7.19-windows-amd64.zip' -OutFile $archive
                if ((Get-FileSha256 $archive) -ne '8e9176aedc87d230ccd97dc7236b16564f2a068609ed301acdc73dc27faf7e77') { throw 'ossutil package checksum mismatch' }
                New-Item -ItemType Directory -Force -Path $install | Out-Null
                Expand-Archive -LiteralPath $archive -DestinationPath $install -Force
                $binary = Get-ChildItem $install -Recurse -Filter 'ossutil*.exe' | Select-Object -First 1
                if (-not $binary) { throw 'ossutil executable is missing from the verified package' }
                Copy-Item $binary.FullName (Join-Path $install 'ossutil.exe') -Force
                $env:PATH = "$install;$env:PATH"
            } finally {
                if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
            }
            continue
        }
        $package = $requirements[$tool]
        if (-not $package) { throw "Required Windows tool is unavailable: $tool" }
        & winget install --source winget --accept-source-agreements --accept-package-agreements --silent --disable-interactivity --id $package --exact
        if ($LASTEXITCODE -ne 0) { throw "Failed to install required Windows tool: $tool" }
    }
}

$terraformCliConfig = & (Join-Path $PSScriptRoot 'configure-terraform-acceleration.ps1')

try {
    Import-AliyunCredential -Profile $Profile -Region $Region
    $identity = Assert-AliyunIdentity -Profile $Profile -ExpectedAccountId $ExpectedAccountId
} catch {
    & (Get-AliyunExecutable) configure --profile $Profile --mode OAuth
    if ($LASTEXITCODE -ne 0) { throw 'Alibaba Cloud OAuth login failed' }
    Import-AliyunCredential -Profile $Profile -Region $Region
    $identity = Assert-AliyunIdentity -Profile $Profile -ExpectedAccountId $ExpectedAccountId
}

[pscustomobject]@{
    platform = 'windows'
    profile = $Profile
    region = $Region
    accountId = [string]$identity.AccountId
    terraformCliConfigFile = [string]$terraformCliConfig
    sessionEnvironmentFile = $null
    validated = $true
} | ConvertTo-Json -Compress

[CmdletBinding()]
param([string]$ConfigDirectory = (Join-Path $env:LOCALAPPDATA 'AutoWonder\Terraform'))

$ErrorActionPreference = 'Stop'
$configFile = Join-Path $ConfigDirectory 'terraform-init-acceleration.tfrc'
New-Item -ItemType Directory -Force -Path $ConfigDirectory | Out-Null
$content = @'
provider_installation {
  network_mirror {
    url = "https://mirrors.aliyun.com/terraform/"
    include = [
      "registry.terraform.io/aliyun/alicloud",
      "registry.terraform.io/hashicorp/alicloud",
    ]
  }
  direct {
    exclude = [
      "registry.terraform.io/aliyun/alicloud",
      "registry.terraform.io/hashicorp/alicloud",
    ]
  }
}
'@
$temporary = "$configFile.$([guid]::NewGuid().ToString('N')).tmp"
try {
    Set-Content -LiteralPath $temporary -Value $content -Encoding utf8NoBOM
    Move-Item -LiteralPath $temporary -Destination $configFile -Force
} finally {
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
}
$env:TF_CLI_CONFIG_FILE = $configFile
$configFile

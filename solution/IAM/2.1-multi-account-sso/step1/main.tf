provider "alicloud" {
  access_key = var.access_key
  secret_key = var.secret_key
  region     = var.region
}

######### 配置资源目录下的成员账号的角色 ###########
# 获取主账号RD下所有子账号
data "alicloud_resource_manager_accounts" "rd" {}

# 导入idp元数据文件
data "external" "metadata_base64" {
  program = ["bash", "-c", <<EOT
    out=$(base64 ${var.metadata});echo "{\"value\": \"$out\"}"
    EOT
  ]
}

resource "local_file" "step2-main" {
  content  = templatefile("${path.module}/step2.tmpl", {
    accounts = [for account in data.alicloud_resource_manager_accounts.rd.accounts: account if !contains(var.exclude, account.account_id)]
    ram_roles = jsonencode(var.ram_roles)
    region = var.region
    access_key = var.access_key
    secret_key = var.secret_key
    metadata = data.external.metadata_base64.result.value
    saml_provider_name = var.saml_provider_name
  })
  filename = "${path.module}/../step2/main.tf"
}

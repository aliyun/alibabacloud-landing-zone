provider "alicloud" {
  access_key = var.access_key
  secret_key = var.secret_key
  region = var.region
}

data "alicloud_account" "current" {
}

data "external" "metadata_base64" {
  program = ["bash", "-c", <<EOT
    out=$(base64 ${var.metadata});echo "{\"value\": \"$out\"}"
    EOT
  ]
}

# 在账号下创建idp
resource "alicloud_ram_saml_provider" "idp" {
  saml_provider_name = var.saml_provider_name
  encodedsaml_metadata_document = data.external.metadata_base64.result.value
  description = "For Terraform Test"
}

# 在账号下创建角色
module "role" {
  for_each = var.ram_roles
  source = "./modules/role"

  providers = {
    alicloud = alicloud
  }

  account_uid = data.alicloud_account.current.id
  role_name = each.key
  role_description = each.value.description
  policies = each.value.policies
  idp_name = var.saml_provider_name
}

output "role_arn" {
  value = <<EOT
    %{ for role_arn in values(module.role)[*]["role_arn"] ~}
      ${role_arn},${alicloud_ram_saml_provider.idp.arn}
    %{ endfor ~}
    EOT
}


# Retrieve current account information
data "alicloud_account" "current" {
}

# Create a saml provider for role based SSO
resource "alicloud_ram_saml_provider" "idp" {
  saml_provider_name = var.saml_provider_name
  encodedsaml_metadata_document = filebase64(var.metadata_file_path)
  description = var.saml_provider_description
}

# Create ram roles
module "role" {
  for_each = {
    for role in var.ram_roles: role.name => role
  }

  source = "./modules/ram_role"

  account_uid = data.alicloud_account.current.id
  role_name = each.value.name
  role_description = each.value.description
  policies = each.value.policies
  idp_name = var.saml_provider_name
}
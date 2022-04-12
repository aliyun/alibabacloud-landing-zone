provider "alicloud" {
  region = "cn-shanghai"
}

module "role_based_sso" {
  source = "../../"
  
  metadata_file_path = var.metadata_file_path

  ram_roles = var.ram_roles

  saml_provider_name = var.saml_provider_name
  saml_provider_description = var.saml_provider_description
}
resource "alicloud_ram_saml_provider" "idp" {
  saml_provider_name            = var.sso_provider_name
  encodedsaml_metadata_document = var.encodedsaml_metadata_document
  description                   = "Created with Terraform automation scripts."
}
terraform {
  required_providers {
    alicloud = {
      source = "aliyun/alicloud"
    }
  }
  required_version = ">=0.12"
}

resource "alicloud_ram_saml_provider" "idp" {
  saml_provider_name            = var.sso_provider_name
  encodedsaml_metadata_document = var.encodedsaml_metadata_document
  description                   = "Created with Terraform automation scripts."
}
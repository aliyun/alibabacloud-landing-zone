# 创建 Production VPC
# 问题参考:https://discuss.hashicorp.com/t/terraform-trying-to-install-incorrect-provider/23747/2
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
  description                   = "通过自动化脚本批量IDP"
}
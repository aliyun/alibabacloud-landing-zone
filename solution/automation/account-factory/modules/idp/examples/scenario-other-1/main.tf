terraform {
  required_providers {
    alicloud = {
      source = "aliyun/alicloud"
    }
  }
  backend "oss" {
  }
}

provider "alicloud" {
  alias = "rd_role"
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", var.account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

module "idp" {
  source = "../../"

  providers = {
    alicloud = alicloud.rd_role
  }
  sso_provider_name = var.sso_provider_name
  encodedsaml_metadata_document = var.encodedsaml_metadata_document
}

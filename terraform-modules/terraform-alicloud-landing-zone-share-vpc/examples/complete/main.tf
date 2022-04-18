terraform {
  required_providers {
    alicloud = {
      source = "aliyun/alicloud"
      version = "1.160.0"
    }
  }
  backend "oss" {
  }
}

provider "alicloud" {
  region = var.shared_region
  alias = "rd_role"
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", var.shared_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}


module "resource_share" {
  source = "../.."
  providers = {
    alicloud = alicloud.rd_role
  }
  shared_unit_name = var.shared_unit_name
  shared_resource_ids = var.shared_resource_ids
  target_account_ids = var.target_account_ids
}

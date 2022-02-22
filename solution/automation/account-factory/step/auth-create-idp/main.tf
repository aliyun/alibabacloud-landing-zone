locals {
  account_json = fileexists("../var/account.json") ? jsondecode(file("../var/account.json")) : {}
  account_id = var.account_id == "" ? local.account_json["account_id"] : var.account_id
  sso_provider_name = var.sso_provider_name
  encodedsaml_metadata_document = var.encodedsaml_metadata_document
}

provider "alicloud" {
  alias = "rd_role"
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

module "idp" {
  source = "../../modules/idp"

  providers = {
    alicloud = alicloud.rd_role
  }
  sso_provider_name = local.sso_provider_name
  encodedsaml_metadata_document = local.encodedsaml_metadata_document
}

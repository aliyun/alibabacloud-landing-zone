locals {
  account_json              = fileexists("../var/account.json") ? jsondecode(file("../var/account.json")) : {}
  dev_account_id            = var.dev_account_id == "" ? local.account_json["dev_account_id"] : var.dev_account_id
  prod_account_id           = var.prod_account_id == "" ? local.account_json["prod_account_id"] : var.prod_account_id
}

provider "alicloud" {
  alias  = "dev_account"
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.dev_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}
module "dev_account_resource_group" {
  source    = "../../modules/resource/resource-group"
  providers = {
    alicloud = alicloud.dev_account
  }
  resource_groups = var.resource_groups
}
provider "alicloud" {
  alias  = "prod_account"
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.prod_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}
module "prod_account_resource_group" {
  source    = "../../modules/resource/resource-group"
  providers = {
    alicloud = alicloud.prod_account
  }
  resource_groups = var.resource_groups
}
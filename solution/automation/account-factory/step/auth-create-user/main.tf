locals {
  account_json = fileexists("../var/account.json") ? jsondecode(file("../var/account.json")) : {}
  account_id   = var.account_id == "" ? local.account_json["account_id"] : var.account_id
  user_name    = var.user_name
}

provider "alicloud" {
  alias = "rd_role"
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

resource "alicloud_ram_user" "user" {
  provider = alicloud.rd_role
  name     = local.user_name
  force    = true
}

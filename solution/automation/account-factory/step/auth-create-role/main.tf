provider "alicloud" {
}

locals {
  account_json      = fileexists("../var/account.json") ? jsondecode(file("../var/account.json")) : {}
  account_id        = var.account_id == "" ? local.account_json["account_id"] : var.account_id
  sso_provider_name = var.sso_provider_name
}

provider "alicloud" {
  alias = "rd_role"
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

# Create ram roles from IDP
module "ram_role" {
  source    = "../../modules/role"
  providers = {
    alicloud = alicloud.rd_role
  }

  for_each          = {for role in var.ram_roles.roles : role.role_name => role}
  account_uid       = local.account_id
  role_name         = each.value.role_name
  role_description  = each.value.description
  sso_provider_name = local.sso_provider_name
}
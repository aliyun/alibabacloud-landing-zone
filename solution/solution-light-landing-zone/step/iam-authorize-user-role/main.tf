locals {
  account_json                     = fileexists("../var/account.json") ? jsondecode(file("../var/account.json")) : {}
  management_account_id            = var.management_account_id == "" ? local.account_json["management_account_id"] : var.management_account_id
  log_account_id                   = var.log_account_id == "" ? local.account_json["log_account_id"] : var.log_account_id
  shared_service_account_id        = var.shared_service_account_id == "" ? local.account_json["shared_service_account_id"] : var.shared_service_account_id
  security_account_id              = var.security_account_id == "" ? local.account_json["security_account_id"] : var.security_account_id
  ops_account_id                   = var.ops_account_id == "" ? local.account_json["ops_account_id"] : var.ops_account_id
  dev_account_id                   = var.dev_account_id == "" ? local.account_json["dev_account_id"] : var.dev_account_id
  prod_account_id                  = var.prod_account_id == "" ? local.account_json["prod_account_id"] : var.prod_account_id
  ram_user_initial_pwd             = var.ram_user_initial_pwd
  management_account_ram_users     = var.management_account_ram_users
  log_account_ram_users            = var.log_account_ram_users
  shared_service_account_ram_users = var.shared_service_account_ram_users
  security_account_ram_users       = var.security_account_ram_users
  ops_account_ram_users            = var.ops_account_ram_users
  dev_account_ram_users            = var.dev_account_ram_users
  prod_account_ram_users           = var.prod_account_ram_users
}


module "management_account_ram_authorize_user_role" {
  source    = "../../modules/ram-authorize-user-role"
  ram_users = local.management_account_ram_users
}


provider "alicloud" {
  alias = "log_account"
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.log_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}
module "log_account_ram_authorize_user_role" {
  source    = "../../modules/ram-authorize-user-role"
  providers = {
    alicloud = alicloud.log_account
  }
  ram_users = local.log_account_ram_users
}


provider "alicloud" {
  alias = "shared_service_account"
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.shared_service_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}
module "shared_service_account_ram_authorize_user_role" {
  source    = "../../modules/ram-authorize-user-role"
  providers = {
    alicloud = alicloud.shared_service_account
  }
  ram_users = local.shared_service_account_ram_users
}


provider "alicloud" {
  alias = "security_account"
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.security_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}
module "security_account_ram_authorize_user_role" {
  source    = "../../modules/ram-authorize-user-role"
  providers = {
    alicloud = alicloud.security_account
  }
  ram_users = local.security_account_ram_users
}


provider "alicloud" {
  alias = "ops_account"
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.ops_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}
module "ops_account_ram_authorize_user_role" {
  source    = "../../modules/ram-authorize-user-role"
  providers = {
    alicloud = alicloud.ops_account
  }
  ram_users = local.ops_account_ram_users
}


provider "alicloud" {
  alias = "dev_account"
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.dev_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}
module "dev_account_ram_authorize_user_role" {
  source    = "../../modules/ram-authorize-user-role"
  providers = {
    alicloud = alicloud.dev_account
  }
  ram_users = local.dev_account_ram_users
}


provider "alicloud" {
  alias = "prod_account"
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.prod_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}
module "prod_account_ram_authorize_user_role" {
  source    = "../../modules/ram-authorize-user-role"
  providers = {
    alicloud = alicloud.prod_account
  }
  ram_users = local.prod_account_ram_users
}
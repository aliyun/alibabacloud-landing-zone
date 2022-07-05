locals {
  account_json              = fileexists("../var/account.json") ? jsondecode(file("../var/account.json")) : {}
  management_account_id     = var.management_account_id == "" ? local.account_json["management_account_id"] : var.management_account_id
  log_account_id            = var.log_account_id == "" ? local.account_json["log_account_id"] : var.log_account_id
  shared_service_account_id = var.shared_service_account_id == "" ? local.account_json["shared_service_account_id"] : var.shared_service_account_id
  security_account_id       = var.security_account_id == "" ? local.account_json["security_account_id"] : var.security_account_id
  ops_account_id            = var.ops_account_id == "" ? local.account_json["ops_account_id"] : var.ops_account_id
  dev_account_id            = var.dev_account_id == "" ? local.account_json["dev_account_id"] : var.dev_account_id
  prod_account_id           = var.prod_account_id == "" ? local.account_json["prod_account_id"] : var.prod_account_id

  shared_service_account_vpc_config = var.shared_service_account_vpc_config
  dev_account_vpc_config            = var.dev_account_vpc_config
  prod_account_vpc_config           = var.prod_account_vpc_config
  ops_account_vpc_config            = var.ops_account_vpc_config
}


provider "alicloud" {
  alias  = "shared_service_account"
  region = local.shared_service_account_vpc_config["region"]
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.shared_service_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

module "shared_service_account_vpc" {
  source                = "../../modules/vpc-create"
  providers             = {
    alicloud = alicloud.shared_service_account
  }
  vpc_name              = local.shared_service_account_vpc_config["vpc_name"]
  vpc_desc              = local.shared_service_account_vpc_config["vpc_desc"]
  vpc_cidr              = local.shared_service_account_vpc_config["vpc_cidr"]
  vpc_tags              = local.shared_service_account_vpc_config["vpc_tags"]
  vswitch_configuration = local.shared_service_account_vpc_config["vswitch"]
}


provider "alicloud" {
  alias  = "dev_account"
  region = local.dev_account_vpc_config["region"]
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.dev_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

module "dev_account_vpc" {
  source                = "../../modules/vpc-create"
  providers             = {
    alicloud = alicloud.dev_account
  }
  vpc_name              = local.dev_account_vpc_config["vpc_name"]
  vpc_desc              = local.dev_account_vpc_config["vpc_desc"]
  vpc_cidr              = local.dev_account_vpc_config["vpc_cidr"]
  vpc_tags              = local.dev_account_vpc_config["vpc_tags"]
  vswitch_configuration = local.dev_account_vpc_config["vswitch"]
}


provider "alicloud" {
  alias  = "prod_account"
  region = local.prod_account_vpc_config["region"]
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.prod_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

module "prod_account_vpc" {
  source                = "../../modules/vpc-create"
  providers             = {
    alicloud = alicloud.prod_account
  }
  vpc_name              = local.prod_account_vpc_config["vpc_name"]
  vpc_desc              = local.prod_account_vpc_config["vpc_desc"]
  vpc_cidr              = local.prod_account_vpc_config["vpc_cidr"]
  vpc_tags              = local.prod_account_vpc_config["vpc_tags"]
  vswitch_configuration = local.prod_account_vpc_config["vswitch"]
}

provider "alicloud" {
  alias  = "ops_account"
  region = local.ops_account_vpc_config["region"]
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.ops_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

module "ops_account_vpc" {
  source                = "../../modules/vpc-create"
  providers             = {
    alicloud = alicloud.ops_account
  }
  vpc_name              = local.ops_account_vpc_config["vpc_name"]
  vpc_desc              = local.ops_account_vpc_config["vpc_desc"]
  vpc_cidr              = local.ops_account_vpc_config["vpc_cidr"]
  vpc_tags              = local.ops_account_vpc_config["vpc_tags"]
  vswitch_configuration = local.ops_account_vpc_config["vswitch"]
}


# Save VPC information
resource "local_file" "account_json" {
  content    = templatefile("../var/vpc.json.tmpl", {
    shared_service_account_vpc_id  = module.shared_service_account_vpc.vpc_id
    shared_service_account_vsw1_id = module.shared_service_account_vpc.vsw1_id
    shared_service_account_vsw2_id = module.shared_service_account_vpc.vsw2_id

    dev_account_vpc_id  = module.dev_account_vpc.vpc_id
    dev_account_vsw1_id = module.dev_account_vpc.vsw1_id
    dev_account_vsw2_id = module.dev_account_vpc.vsw2_id

    prod_account_vpc_id  = module.prod_account_vpc.vpc_id
    prod_account_vsw1_id = module.prod_account_vpc.vsw1_id
    prod_account_vsw2_id = module.prod_account_vpc.vsw2_id

    ops_account_vpc_id  = module.ops_account_vpc.vpc_id
    ops_account_vsw1_id = module.ops_account_vpc.vsw1_id
    ops_account_vsw2_id = module.ops_account_vpc.vsw2_id

  })
  filename   = "../var/vpc.json"
  depends_on = [
    module.shared_service_account_vpc, module.dev_account_vpc, module.prod_account_vpc, module.ops_account_vpc
  ]
}

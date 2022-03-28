locals {
  account_json = fileexists("../var/account.json") ? jsondecode(file("../var/account.json")) : {}
  account_id = var.account_id == "" ? local.account_json["account_id"] : var.account_id
  vpc_name = var.vpc_name
  vpc_cidr_block = var.vpc_cidr_block
  switch_cidr_block = var.switch_cidr_block
  vswitch_name = var.vswitch_name
  zone_id = var.zone_id
}

provider "alicloud" {
  alias = "rd_role"
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

module "network_config" {
  source = "../../modules/network"
  providers = {
    alicloud = alicloud.rd_role
  }

  vpc_name = local.vpc_name
  vpc_cidr_block = local.vpc_cidr_block
  vswitch_name = local.vswitch_name
  switch_cidr_block = local.switch_cidr_block
  zone_id = local.zone_id
}

# Save information temporarily, can be used in subsequent steps
resource "local_file" "vpc_json" {
  content  = templatefile("../var/vpc.json.tmpl", {
    vpc_name = local.vpc_name
    vpc_id = module.network_config.vpc_id
  })
  filename = "../var/vpc.json"
}

resource "local_file" "vswitch_json" {
  content  = templatefile("../var/vswitch.json.tmpl", {
    vswitch_name = local.vswitch_name
    vswitch_id = module.network_config.vswitch_id
  })
  filename = "../var/vswitch.json"
}
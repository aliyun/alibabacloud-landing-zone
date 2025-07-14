locals {
  account_json = fileexists("../var/account.json") ? jsondecode(file("../var/account.json")) : {}

  dev_account_id = var.dev_account_id == "" ? local.account_json["dev_account_id"] : var.dev_account_id
  prod_account_id = var.prod_account_id == "" ? local.account_json["prod_account_id"] : var.prod_account_id

  dev_snat_source_cidr_list = [
    var.dev_account_vpc_config["vpc_cidr"],
  ]
  prod_snat_source_cidr_list = [
    var.prod_account_vpc_config["vpc_cidr"],
  ]
  prod_egress_eip_name = var.prod_egress_eip_name
  daily_egress_eip_name = var.daily_egress_eip_name
  prod_egress_nat_gateway_name = var.prod_egress_nat_gateway_name
  daily_egress_nat_gateway_name = var.daily_egress_nat_gateway_name
  dev_account_vpc_config = var.dev_account_vpc_config
  prod_account_vpc_config = var.prod_account_vpc_config

  vpc_json = fileexists("../var/vpc.json") ? jsondecode(file("../var/vpc.json")) : {}

  dev_account_vpc_id = var.dev_account_vpc_id == "" ? local.vpc_json["dev_account"]["vpc_id"] : var.dev_account_vpc_id
  dev_account_vswitch_id = var.dev_account_vswitch_id == "" ? local.vpc_json["dev_account"]["vsw1_id"] : var.dev_account_vswitch_id

  prod_account_vpc_id = var.prod_account_vpc_id == "" ? local.vpc_json["prod_account"]["vpc_id"] : var.prod_account_vpc_id
  prod_account_vswitch_id = var.prod_account_vswitch_id == "" ? local.vpc_json["prod_account"]["vsw1_id"] : var.prod_account_vswitch_id


}


provider "alicloud" {
  alias = "dev_account"
  region = local.dev_account_vpc_config["region"]
  assume_role {
    role_arn = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.dev_account_id)
    session_name = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

module "daily_account_dmz_eip" {
  source = "../../modules/networking/eip"
  providers = {
    alicloud = alicloud.dev_account
  }

  eip_config = [
    {
      payment_type = "PayAsYouGo"
      eip_address_name = "eip-dmz"
      period = null
      tags = {
        "Environment" = "shared"
        "Department" = "ops"
      }
    }
  ]
  create_common_bandwidth_package = true
  common_bandwidth_package_bandwidth = 5
  common_bandwidth_package_internet_charge_type = "PayByBandwidth"
}

module "dev_account_dmz_nat_gateway" {
  source = "../../modules/networking/nat-gateway"
  providers = {
    alicloud = alicloud.dev_account
  }
  vpc_id = local.dev_account_vpc_id
  name = local.daily_egress_nat_gateway_name
  vswitch_id = local.dev_account_vswitch_id
  association_eip_id_list = module.daily_account_dmz_eip.eip_id_list

  snat_source_cidr_list = local.dev_snat_source_cidr_list
  snat_ip_list = module.daily_account_dmz_eip.eip_address_list
}


provider "alicloud" {
  alias = "prod_account"
  region = local.dev_account_vpc_config["region"]
  assume_role {
    role_arn = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.prod_account_id)
    session_name = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

module "prod_account_dmz_eip" {
  source = "../../modules/networking/eip"
  providers = {
    alicloud = alicloud.prod_account
  }

  eip_config = [
    {
      payment_type = "PayAsYouGo"
      eip_address_name = "eip-dmz"
      period = null
      tags = {
        "Environment" = "shared"
        "Department" = "ops"
      }
    }
  ]
  create_common_bandwidth_package = true
  common_bandwidth_package_bandwidth = 5
  common_bandwidth_package_internet_charge_type = "PayByBandwidth"
}

module "prod_account_dmz_nat_gateway" {
  source = "../../modules/networking/nat-gateway"
  providers = {
    alicloud = alicloud.prod_account
  }
  vpc_id = local.prod_account_vpc_id
  name = local.prod_egress_nat_gateway_name
  vswitch_id = local.prod_account_vswitch_id
  association_eip_id_list = module.prod_account_dmz_eip.eip_id_list

  snat_source_cidr_list = local.prod_snat_source_cidr_list
  snat_ip_list = module.prod_account_dmz_eip.eip_address_list
}


# Save dmz information
resource "local_file" "account_json" {
  content = templatefile("../var/dmz.json.tmpl", {
    dev_egress_eip_id = join(",", module.daily_account_dmz_eip.eip_id_list)
    dev_egress_eip_ip_address = join(",", module.daily_account_dmz_eip.eip_address_list)
    dev_egress_nat_gateway_id = module.dev_account_dmz_nat_gateway.nat_gateway_id

    prod_egress_eip_id = join(",", module.prod_account_dmz_eip.eip_id_list)
    prod_egress_eip_ip_address = join(",", module.prod_account_dmz_eip.eip_address_list)
    prod_egress_nat_gateway_id = module.prod_account_dmz_nat_gateway.nat_gateway_id
  })
  filename = "../var/dmz.json"
}

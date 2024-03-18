locals {
  account_json              = fileexists("../var/account.json") ? jsondecode(file("../var/account.json")) : {}
  shared_service_account_id = var.shared_service_account_id == "" ? local.account_json["shared_service_account_id"] : var.shared_service_account_id

  snat_source_cidr_list = [
    var.shared_service_account_vpc_config["vpc_cidr"],
    var.dev_account_vpc_config["vpc_cidr"],
    var.prod_account_vpc_config["vpc_cidr"],
    var.ops_account_vpc_config["vpc_cidr"]
  ]

  dmz_egress_eip_name         = var.dmz_egress_eip_name
  dmz_egress_nat_gateway_name = var.dmz_egress_nat_gateway_name

  vpc_json                          = fileexists("../var/vpc.json") ? jsondecode(file("../var/vpc.json")) : {}
  shared_service_account_vpc_id     = var.shared_service_account_vpc_id == "" ? local.vpc_json["shared_service_account"]["vpc_id"] : var.shared_service_account_vpc_id
  shared_service_account_vswitch_id = var.shared_service_account_vswitch_id == "" ? local.vpc_json["shared_service_account"]["vsw1_id"] : var.shared_service_account_vswitch_id
}


provider "alicloud" {
  alias  = "shared_service_account"
  region = var.shared_service_account_vpc_config["region"]
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.shared_service_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

module "shared_service_account_dmz_eip" {
  source    = "../../modules/networking/eip"
  providers = {
    alicloud = alicloud.shared_service_account
  }

  eip_config                                    = [
    {
      payment_type     = "PayAsYouGo"
      eip_address_name = "eip-dmz"
      period           = null
      tags             = {
        "Environment" = "shared"
        "Department"  = "ops"
      }
    }
  ]
  create_common_bandwidth_package               = true
  common_bandwidth_package_bandwidth            = 5
  common_bandwidth_package_internet_charge_type = "PayByBandwidth"
}

module "shared_service_account_dmz_nat_gateway" {
  source                  = "../../modules/networking/nat-gateway"
  providers               = {
    alicloud = alicloud.shared_service_account
  }
  vpc_id                  = local.shared_service_account_vpc_id
  name                    = local.dmz_egress_nat_gateway_name
  vswitch_id              = local.shared_service_account_vswitch_id
  association_eip_id_list = module.shared_service_account_dmz_eip.eip_id_list

  snat_source_cidr_list = local.snat_source_cidr_list
  snat_ip_list          = module.shared_service_account_dmz_eip.eip_address_list
}


# Save dmz information
resource "local_file" "account_json" {
  content  = templatefile("../var/dmz.json.tmpl", {
    egress_eip_id         = join(",", module.shared_service_account_dmz_eip.eip_id_list)
    egress_eip_ip_address = join(",", module.shared_service_account_dmz_eip.eip_address_list)
    egress_nat_gateway_id = module.shared_service_account_dmz_nat_gateway.nat_gateway_id
  })
  filename = "../var/dmz.json"
}

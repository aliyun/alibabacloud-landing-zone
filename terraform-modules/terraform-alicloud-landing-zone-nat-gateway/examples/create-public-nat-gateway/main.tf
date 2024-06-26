terraform {
  required_providers {
    alicloud = {
      source  = "aliyun/alicloud"
      version = "1.160.0"
    }
  }
}

provider "alicloud" {
  region = var.region
}

module "dmz_egress_eip" {
  source    = "../../../terraform-alicloud-landing-zone-eip"
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


module "dmz_egress_nat_gateway" {
  source                  = "../.."
  vpc_id                  = var.dmz_vpc_id
  name                    = var.nat_gateway_name
  vswitch_id              = var.vswitch_id_nat_gateway
  snat_source_cidr_list   = var.snat_source_cidr_list
  association_eip_id_list = module.dmz_egress_eip.eip_id_list
  snat_ip_list            = module.dmz_egress_eip.eip_address_list
}

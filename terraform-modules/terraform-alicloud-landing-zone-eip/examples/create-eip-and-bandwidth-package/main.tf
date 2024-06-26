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
  source    = "../.."
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
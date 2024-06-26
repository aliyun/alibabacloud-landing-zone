provider "alicloud" {
  region = var.region
}

provider "alicloud" {
  alias  = "dmz_vpc_account"
  region = var.region
}

module "dmz_vpc" {
  source = "../../"

  providers = {
    alicloud = alicloud
    alicloud.cen_account     = alicloud
    alicloud.dmz_vpc_account = alicloud.dmz_vpc_account
  }

  dmz_vpc_name                           = "dmz-vpc"
  dmz_vpc_description                    = "dmz vpc for network inbound and outbound"
  dmz_vpc_cidr                           = var.dmz_vpc_cidr_block
  dmz_egress_nat_gateway_name            = "dmz-egress-nat-gateway"
  dmz_egress_eip_count                   = var.dmz_egress_eip_count
  dmz_egress_eip_name_prefix             = "dmz-egress-eip-"
  dmz_enable_common_bandwidth_package    = var.dmz_enable_common_bandwidth_package
  dmz_common_bandwidth_package_bandwidth = var.dmz_common_bandwidth_package_bandwidth
  dmz_vswitch = [
    for idx, vsw in var.dmz_vswitch_list :
    {
      zone_id             = vsw.zone_id
      vswitch_name        = format("dmz-vsw-%s", idx + 1)
      vswitch_description = format("dmz vswitch %s", idx + 1)
      vswitch_cidr        = vsw.vswitch_cidr
    }
  ]
  dmz_vswitch_for_tr = [
    for idx, vsw in var.dmz_vswitch_for_tr :
    {
      zone_id             = vsw.zone_id
      vswitch_name        = format("dmz-vsw-tr-%s", idx + 1)
      vswitch_description = format("dmz vswitch-%s for TR", idx + 1)
      vswitch_cidr        = vsw.vswitch_cidr
    }
  ]
  dmz_vswitch_for_nat_gateway = {
    zone_id             = var.dmz_vswitch_for_nat_gateway.zone_id
    vswitch_cidr        = var.dmz_vswitch_for_nat_gateway.vswitch_cidr
    vswitch_name        = "dmz-vsw-nat-gateway"
    vswitch_description = "dmz vswitch for nat gateway"
  }
}

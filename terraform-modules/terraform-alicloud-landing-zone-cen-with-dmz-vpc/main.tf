data "alicloud_account" "cen" {

}

data "alicloud_account" "dmz_vpc" {
  provider = alicloud.dmz_vpc_account
}

resource "alicloud_cen_instance" "cen" {
  cen_instance_name = var.cen_instance_name
  description       = var.cen_instance_description
  # tags              = var.cen_instance_tags
}

resource "alicloud_cen_transit_router" "cen_tr" {
  cen_id              = alicloud_cen_instance.cen.id
  transit_router_name = var.cen_transit_router_name
}

locals {
  cen_instance_id       = alicloud_cen_instance.cen.id
  cen_transit_router_id = alicloud_cen_transit_router.cen_tr.transit_router_id
}

resource "alicloud_vpc" "dmz_vpc" {
  vpc_name    = var.dmz_vpc_name
  cidr_block  = var.dmz_vpc_cidr
  description = var.dmz_vpc_description
}

resource "alicloud_vswitch" "dmz_vswitch_for_tr" {
  for_each = {
    for idx, vsw in var.dmz_vswitch_for_tr : idx => vsw
  }

  vpc_id       = alicloud_vpc.dmz_vpc.id
  cidr_block   = each.value.vswitch_cidr
  zone_id      = each.value.zone_id
  vswitch_name = each.value.vswitch_name
  description  = each.value.vswitch_description
}

resource "alicloud_vswitch" "dmz_vswitch_for_nat_gateway" {
  vpc_id       = alicloud_vpc.dmz_vpc.id
  cidr_block   = var.dmz_vswitch_for_nat_gateway.vswitch_cidr
  zone_id      = var.dmz_vswitch_for_nat_gateway.zone_id
  vswitch_name = var.dmz_vswitch_for_nat_gateway.vswitch_name
  description  = var.dmz_vswitch_for_nat_gateway.vswitch_description
}

resource "alicloud_vswitch" "dmz_vswitch" {
  for_each = {
    for idx, vsw in var.dmz_vswitch : idx => vsw
  }

  vpc_id       = alicloud_vpc.dmz_vpc.id
  cidr_block   = each.value.vswitch_cidr
  zone_id      = each.value.zone_id
  vswitch_name = each.value.vswitch_name
  description  = each.value.vswitch_description
}

# Create EIP instances which will be attached to NAT Gateway.
module "dmz_eip" {
  source = "./modules/eip"

  eip_config = [
    for i in range(0, var.dmz_egress_eip_count) :
    {
      payment_type     = "PayAsYouGo"
      eip_address_name = format("%s%s", var.dmz_egress_eip_name_prefix, i + 1)
      period           = null
      tags             = {}
    }
  ]
  create_common_bandwidth_package               = var.dmz_enable_common_bandwidth_package
  common_bandwidth_package_bandwidth            = var.dmz_common_bandwidth_package_bandwidth
  common_bandwidth_package_internet_charge_type = "PayByBandwidth"
}

module "dmz_nat_gateway" {
  source = "./modules/nat-gateway"

  vpc_id                  = alicloud_vpc.dmz_vpc.id
  name                    = var.dmz_egress_nat_gateway_name
  vswitch_id              = alicloud_vswitch.dmz_vswitch_for_nat_gateway.id
  association_eip_id_list = module.dmz_eip.eip_id_list
  snat_source_cidr_list   = []
  snat_ip_list            = module.dmz_eip.eip_address_list
}

module "dmz_vpc_attach_to_cen" {
  source = "./modules/cen-vpc-attach"

  providers = {
    alicloud.cen_account = alicloud
    alicloud.vpc_account = alicloud.dmz_vpc_account
  }

  cen_tr_account_id = data.alicloud_account.cen.id
  vpc_account_id = data.alicloud_account.dmz_vpc.id
  create_cen_linked_role = false
  cen_instance_id        = local.cen_instance_id
  cen_transit_router_id  = local.cen_transit_router_id
  transit_router_attachment_name = "dmz-vpc-tr"
  transit_router_attachment_desc = "tr for dmz vpc"
  vpc_id = alicloud_vpc.dmz_vpc.id
  primary_vswitch = {
    vswitch_id = alicloud_vswitch.dmz_vswitch_for_tr[0].id
    zone_id = alicloud_vswitch.dmz_vswitch_for_tr[0].availability_zone
  }
  secondary_vswitch = {
    vswitch_id = alicloud_vswitch.dmz_vswitch_for_tr[1].id
    zone_id = alicloud_vswitch.dmz_vswitch_for_tr[1].availability_zone
  }
  route_table_association_enabled = true
  route_table_propagation_enabled = true
}

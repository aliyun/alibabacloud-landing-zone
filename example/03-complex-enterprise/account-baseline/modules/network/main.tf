data "alicloud_vpcs" "vpc_app_ds" {
  ids = [
    var.vpc_id
  ]
}

module "vpc_vswitch" {
  source = "./vswitch"

  for_each          = {
    for vsw in var.app_network_setting.vswitches : "${vsw.vswitch_name}" => vsw
  }

  vswitch_name      = each.value.vswitch_name
  vpc_id            = var.vpc_id
  cidr_block        = each.value.cidr_block
  zone = each.value.zone
  resource_share_id = var.resource_share_id
}

# resource "alicloud_cen_instance_attachment" "cen_app_network_attachment" {
#   instance_id              = var.cen_instance_id
#   child_instance_id        = data.alicloud_vpcs.vpc_app_ds.vpcs.0.id
#   child_instance_region_id = data.alicloud_vpcs.vpc_app_ds.vpcs.0.region_id
# }

module "vpc_nacl" {
  source = "./nacl"

  count = var.network_acl_enabled ? 1 : 0

  vpc_id = var.vpc_id
  network_acl_name = "app_acl"
  vswitches = {
    for o in keys(module.vpc_vswitch) : o => module.vpc_vswitch[o].vswitch_app
  }
  vswitches_shared_services = var.vswitches_shared_services
  vswitches_dmz = var.vswitches_dmz
}
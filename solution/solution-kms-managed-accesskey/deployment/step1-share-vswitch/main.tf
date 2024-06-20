provider "alicloud" {
  region = var.region
}

resource "alicloud_resource_manager_resource_share" "share_vswitch" {
  resource_share_name = var.resource_share_name
}

resource "alicloud_resource_manager_shared_target" "share_vswitch" {
  resource_share_id = alicloud_resource_manager_resource_share.share_vswitch.id
  target_id         = var.resource_share_target_id
}

resource "alicloud_resource_manager_shared_resource" "share_vswitch" {
  for_each = {
    for vswitch_id in var.vswitch_ids : vswitch_id => vswitch_id
  }
  resource_id       = each.value
  resource_share_id = alicloud_resource_manager_resource_share.share_vswitch.id
  resource_type     = "VSwitch"
}

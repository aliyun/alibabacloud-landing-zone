resource "alicloud_resource_manager_resource_share" "res_share_1" {
  resource_share_name = var.shared_unit_name
}

resource "alicloud_resource_manager_shared_resource" "shared_res_1" {
  resource_share_id = alicloud_resource_manager_resource_share.res_share_1.id
  for_each          = toset(var.shared_resource_ids)
  resource_id       = each.value
  resource_type     = "VSwitch"
}

resource "alicloud_resource_manager_shared_target" "shared_target_1" {
  resource_share_id = alicloud_resource_manager_resource_share.res_share_1.id
  for_each          = toset(var.target_account_ids)
  target_id         = each.value
}
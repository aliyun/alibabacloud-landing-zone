provider "alicloud" {
  region = var.region
}

resource "alicloud_resource_manager_resource_share" "share_kms_instance" {
  resource_share_name = var.resource_share_name
}

resource "alicloud_resource_manager_shared_target" "share_kms_instance" {
  resource_share_id = alicloud_resource_manager_resource_share.share_kms_instance.id
  target_id         = var.resource_share_target_id
}

resource "alicloud_resource_manager_shared_resource" "share_kms_instance" {
  for_each = {
    for kms_instance_id in var.kms_instance_ids : kms_instance_id => kms_instance_id
  }
  resource_id       = each.value
  resource_share_id = alicloud_resource_manager_resource_share.share_kms_instance.id
  resource_type     = "KMSInstance"
}

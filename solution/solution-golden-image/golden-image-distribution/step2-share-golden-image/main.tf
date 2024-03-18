provider "alicloud" {
  region = var.region
}

resource "alicloud_resource_manager_resource_share" "share_image" {
  resource_share_name = var.resource_share_name
}

resource "alicloud_resource_manager_shared_target" "share_image" {
  resource_share_id = alicloud_resource_manager_resource_share.share_image.id
  target_id         = var.resource_share_target_id
}

resource "alicloud_resource_manager_shared_resource" "share_image" {
  for_each = {
    for image_id in var.golden_image_ids : image_id => image_id
  }
  resource_id       = each.value
  resource_share_id = alicloud_resource_manager_resource_share.share_image.id
  resource_type     = "Image"
}

output "resource_share_id" {
  value = alicloud_resource_manager_resource_share.res_share_1.id
}

output "resource_share_owner" {
  value = alicloud_resource_manager_resource_share.res_share_1.resource_share_owner
}

output "resource_share_status" {
  value = alicloud_resource_manager_resource_share.res_share_1.status
}

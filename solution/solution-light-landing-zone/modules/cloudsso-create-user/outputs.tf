output "cloudsso_user_id" {
  description = "当前创建出来新用户的ID"
  value       = concat(alicloud_cloud_sso_user.default.*.id)
}


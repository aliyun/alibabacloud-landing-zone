output "application_account_id" {
  value       = alicloud_resource_manager_account.rd_account_app.id
  description = "Output application accounts ids."
}

output "account_setting" {
  value       = var.app_setting
}
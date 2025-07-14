output "resource_directory_id" {
  value = alicloud_resource_manager_resource_directory.master.id
}

output "root_folder_id" {
  value = alicloud_resource_manager_resource_directory.master.root_folder_id
}

output "core_folder_id" {
  value = alicloud_resource_manager_folder.core.id
}

output "applications_folder_id" {
  value = alicloud_resource_manager_folder.applications.id
}

output "shared_services_account_id" {
  value = alicloud_resource_manager_account.shared_services.id
}

output "log_archive_account_id" {
  value = alicloud_resource_manager_account.log_archive.id
}

output "security_account_id" {
  value = alicloud_resource_manager_account.security_service.id
}
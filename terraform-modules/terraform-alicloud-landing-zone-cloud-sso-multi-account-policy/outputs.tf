output "directory_id" {
  description = "The id of cloud sso directory of this module used."
  value       = local.this_directory_id
}
output "folder_id" {
  description = "The id of resource manager folder."
  value       = local.this_folder_id
}
output "folder_name" {
  description = "The name of resource manager folder."
  value       = var.folder_name
}
output "account_id" {
  description = "The id of resource manager account."
  value       = local.this_account_id
}
output "group_ids" {
  description = "List of ids of cloud sso user group."
  value       = local.matched_groups.*.group_id
}
output "group_names" {
  description = "List of names of cloud sso user group."
  value       = local.matched_groups.*.group_name
}
output "access_configuration_ids" {
  description = "List of ids of cloud sso access configuration."
  value       = local.matched_access_configurations.*.access_configuration_id
}
output "access_configuration_names" {
  description = "List of names of cloud sso access configuration."
  value       = local.matched_access_configurations.*.access_configuration_name
}
output "create_directory" {
  description = "Whether a new cloud sso directory was created."
  value       = local.create_directory
}
output "create_resource_manager_folder" {
  description = "Whether a new resource manager folder was created."
  value       = local.create_resource_manager_folder
}

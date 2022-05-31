output "directory_id" {
  description = "The id of cloud sso directory of this module used."
  value       = module.multi-account.directory_id
}
output "folder_id" {
  description = "The id of resource manager folder."
  value       = module.multi-account.folder_id
}
output "folder_name" {
  description = "The name of resource manager folder."
  value       = module.multi-account.folder_name
}
output "account_id" {
  description = "The id of resource manager account."
  value       = module.multi-account.account_id
}
output "group_ids" {
  description = "List of ids of cloud sso user group."
  value       = module.multi-account.group_ids
}
output "group_names" {
  description = "List of names of cloud sso user group."
  value       = module.multi-account.group_names
}

output "access_configuration_ids" {
  description = "List of ids of cloud sso access configuration."
  value       = module.multi-account.access_configuration_ids
}
output "access_configuration_names" {
  description = "List of names of cloud sso access configuration."
  value       = module.multi-account.access_configuration_names
}
output "create_directory" {
  description = "Whether a new cloud sso directory was created."
  value       = module.directory.create_directory
}
output "create_resource_manager_folder" {
  description = "Whether a new resource manager folder was created."
  value       = module.directory.create_resource_manager_folder
}
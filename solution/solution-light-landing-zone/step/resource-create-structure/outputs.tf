# outputs.tf https://learn.hashicorp.com/tutorials/terraform/outputs
output "resource_directory_id" {
  value = module.resource_directory.resource_directory_id
}

output "root_folder_id" {
  value = module.resource_directory.root_folder_id
}
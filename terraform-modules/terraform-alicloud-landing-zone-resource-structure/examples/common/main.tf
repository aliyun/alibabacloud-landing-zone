provider "alicloud" {
  region = "cn-shanghai"
}

module "directory" {
  source = "../../"
  
  core_folder_name = var.core_folder_name
  applications_folder_name = var.applications_folder_name
  shared_services_account_name = var.shared_services_account_name
  log_archive_account_name = var.log_archive_account_name
  billing_account_uid = var.billing_account_uid
}
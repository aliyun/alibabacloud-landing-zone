# Retrieve current account information
data "alicloud_account" "current_account" {
}

# Activate resource directory service
resource "alicloud_resource_manager_resource_directory" "master" {}

# Create folder Core
resource "alicloud_resource_manager_folder" "core" {
  folder_name = var.core_folder_name

  depends_on = [
    alicloud_resource_manager_resource_directory.master
  ]
}

# Create folder Applications
resource "alicloud_resource_manager_folder" "applications" {
  folder_name = var.applications_folder_name

  depends_on = [
    alicloud_resource_manager_resource_directory.master
  ]
}

locals {
  billing_account_uid = var.billing_account_uid != "" ? var.billing_account_uid : data.alicloud_account.current_account.id
}

# Create SharedServices account in core folder
resource "alicloud_resource_manager_account" "shared_services" {
  display_name = var.shared_services_account_name
  folder_id = alicloud_resource_manager_folder.core.id
  payer_account_id = local.billing_account_uid
  account_name_prefix = var.shared_services_account_name
}

# Create LogArchive account in core folder
resource "alicloud_resource_manager_account" "log_archive" {
  display_name = var.log_archive_account_name
  folder_id = alicloud_resource_manager_folder.core.id
  payer_account_id = local.billing_account_uid
  account_name_prefix = var.log_archive_account_name
  depends_on = [alicloud_resource_manager_account.shared_services]
}

# Create Secutiry account in core folder
resource "alicloud_resource_manager_account" "security_service" {
  display_name = var.log_archive_account_name
  folder_id = alicloud_resource_manager_folder.core.id
  payer_account_id = local.billing_account_uid
  account_name_prefix = var.security_account_name
  depends_on = [alicloud_resource_manager_account.log_archive]
}




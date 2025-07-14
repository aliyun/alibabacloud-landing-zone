provider "alicloud" {
  configuration_source = "AlibabaCloud/LightLandingZone 1.0"
}

module "resource_directory" {
  source = "../../modules/terraform-alicloud-landing-zone-resource-structure"

  core_folder_name = var.core_folder_name
  applications_folder_name = var.applications_folder_name
  shared_services_account_name = var.shared_services_account_name
  log_archive_account_name = var.log_archive_account_name
  security_account_name = var.security_account_name
  billing_account_uid = var.payer_account_id
}


resource "alicloud_resource_manager_account" "ops_account" {
  display_name = var.ops_display_name
  account_name_prefix = var.ops_account_name_prefix
  folder_id = module.resource_directory.core_folder_id
  payer_account_id = var.payer_account_id
  depends_on = [module.resource_directory]
}

resource "alicloud_resource_manager_account" "dev_account" {
  display_name = var.daily_display_name
  account_name_prefix = var.daily_account_name_prefix
  folder_id = module.resource_directory.applications_folder_id
  payer_account_id = var.payer_account_id
  depends_on = [
    alicloud_resource_manager_account.ops_account]
}

resource "alicloud_resource_manager_account" "prod_account" {
  display_name = var.prod_display_name
  account_name_prefix = var.prod_account_name_prefix
  folder_id = module.resource_directory.applications_folder_id
  payer_account_id = var.payer_account_id
  depends_on = [
    alicloud_resource_manager_account.dev_account]

}

# Save member account information
resource "local_file" "account_json" {
  content = templatefile("../var/account_overall.json.tmpl", {
    shared_service_account_id = module.resource_directory.shared_services_account_id
    security_account_id = module.resource_directory.security_account_id
    log_account_id = module.resource_directory.log_archive_account_id
    ops_account_id = alicloud_resource_manager_account.ops_account.id
    dev_account_id = alicloud_resource_manager_account.dev_account.id
    prod_account_id = alicloud_resource_manager_account.prod_account.id
    application_folder_id = module.resource_directory.applications_folder_id
  })
  filename = "../var/account.json"
}

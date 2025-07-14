provider "alicloud" {
}

# gcg can create log, shardservice,security account. now create member account by terraform
resource "alicloud_resource_manager_account" "ops_account" {
  display_name = var.ops_display_name
  account_name_prefix = var.ops_account_name_prefix
  folder_id = var.core_folder_id
  payer_account_id = var.payer_account_id
}

resource "alicloud_resource_manager_account" "dev_account" {
  display_name = var.daily_display_name
  account_name_prefix = var.daily_account_name_prefix
  folder_id = var.applications_folder_id
  payer_account_id = var.payer_account_id
  depends_on = [alicloud_resource_manager_account.ops_account]
}

resource "alicloud_resource_manager_account" "prod_account" {
  display_name = var.prod_display_name
  account_name_prefix = var.prod_account_name_prefix
  folder_id = var.applications_folder_id
  payer_account_id = var.payer_account_id
  depends_on = [alicloud_resource_manager_account.dev_account]

}

# Save member account information
resource "local_file" "account_json" {
  content  = templatefile("../var/account.json.tmpl", {
    ops_account_id       = alicloud_resource_manager_account.ops_account.id
    dev_account_id      = alicloud_resource_manager_account.dev_account.id
    prod_account_id     = alicloud_resource_manager_account.prod_account.id
  })
  filename = "../var/account.json"
}

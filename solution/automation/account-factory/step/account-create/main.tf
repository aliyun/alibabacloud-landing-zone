provider "alicloud" {
}

# Create member account
resource "alicloud_resource_manager_account" "rd_account_app" {
  display_name = var.account_name
  account_name_prefix = var.account_name_prefix
  folder_id = var.folder_id
}

# Save member account information temporarily
resource "local_file" "account_json" {
  content  = templatefile("../var/account.json.tmpl", {
    account_id = alicloud_resource_manager_account.rd_account_app.id
  })
  filename = "../var/account.json"
}

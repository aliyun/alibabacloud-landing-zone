provider "alicloud" {
}

# 创建成员账号
resource "alicloud_resource_manager_account" "rd_account_app" {
  display_name = var.account_name
  account_name_prefix = var.account_name_prefix
  folder_id = var.folder_id
}

# 保存成员账号的account信息
resource "local_file" "account_json" {
  content  = templatefile("../var/account.json.tmpl", {
    account_id = alicloud_resource_manager_account.rd_account_app.id
  })
  filename = "../var/account.json"
}

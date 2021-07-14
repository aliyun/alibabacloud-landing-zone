# 创建文件夹
resource "alicloud_resource_manager_folder" "folder" {
  folder_name = var.folder_name
}

# 在文件夹下创建账户
resource "alicloud_resource_manager_account" "account" {
  for_each = toset(var.account_name)
  display_name = each.key
  folder_id = alicloud_resource_manager_folder.folder.id
}

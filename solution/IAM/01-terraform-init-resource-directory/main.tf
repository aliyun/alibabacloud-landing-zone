 provider "alicloud" {
  access_key = var.access_key
  secret_key = var.secret_key
  region = var.region
}

# 开通资源目录
resource "alicloud_resource_manager_resource_directory" "resource_manager" {
  # 开启策略管控，目前有bug，无法在RD创建时开启，会被流控；如需开启可以在运行完成后，手动将status修改为Enabled状态
  status = "Enabled"
}

# 在文件夹下创建资源账号
module "account" {
  for_each = var.resource_directories
  source = "./modules/account"

  providers = {
    alicloud = alicloud
  }

  folder_name = each.key
  account_name = each.value.users
  depends_on = [
    alicloud_resource_manager_resource_directory.resource_manager,
  ]
}
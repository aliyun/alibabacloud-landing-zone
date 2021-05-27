provider "alicloud" {
  access_key = var.access_key
  secret_key = var.secret_key
  region = var.region
}

# 根据目标资源夹的正则表达式查询资源夹
data "alicloud_resource_manager_folders" "folder" {
  name_regex = var.directory_regex
}

# 创建策略
resource "alicloud_resource_manager_control_policy" "control_policy" {
  control_policy_name = var.control_policy_name
  description = var.description
  effect_scope = var.effect_scope
  policy_document = jsonencode(var.policy_document)
}

# 将策略添加到资源夹下
resource "alicloud_resource_manager_control_policy_attachment" "attach" {
  for_each = toset(data.alicloud_resource_manager_folders.folder.ids)
  policy_id = alicloud_resource_manager_control_policy.control_policy.id
  target_id = each.value
}

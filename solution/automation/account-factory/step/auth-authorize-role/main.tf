locals {
  account_json = fileexists("../var/account.json") ? jsondecode(file("../var/account.json")) : {}
  account_id   = var.account_id == "" ? local.account_json["account_id"] : var.account_id

  # Contributor 参数
  policy_name     = var.policy_name
  policy_document = var.policy_document
  attach_roles    = var.attach_roles
  attach_users    = var.attach_users

  # Reader 参数
  reader_name        = var.reader_name
  reader_policy_type = var.reader_policy_type
  reader_policy_name = var.reader_policy_name
}

provider "alicloud" {
  alias = "rd_role"
  # 目前TF无法支持动态provider功能
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

# 创建Contributor策略，并为Ram role和Ram user添加策略
resource "alicloud_ram_policy" "policy" {
  provider        = alicloud.rd_role
  policy_name     = local.policy_name
  policy_document = local.policy_document
  description     = "create by Terraform"
  force           = true
}

resource "alicloud_ram_user_policy_attachment" "user_attach" {
  provider    = alicloud.rd_role
  for_each    = toset(local.attach_users)
  policy_name = alicloud_ram_policy.policy.name
  policy_type = alicloud_ram_policy.policy.type
  user_name   = each.value
}

resource "alicloud_ram_role_policy_attachment" "role_attach" {
  provider    = alicloud.rd_role
  for_each    = toset(local.attach_roles)
  policy_name = alicloud_ram_policy.policy.name
  policy_type = alicloud_ram_policy.policy.type
  role_name   = each.value
}

# 为reader添加策略
resource "alicloud_ram_role_policy_attachment" "reader_attach" {
  provider    = alicloud.rd_role
  policy_name = local.reader_policy_name
  policy_type = local.reader_policy_type
  role_name   = local.reader_name
}
locals {
  account_json = fileexists("../var/account.json") ? jsondecode(file("../var/account.json")) : {}
  account_id   = var.account_id == "" ? local.account_json["account_id"] : var.account_id

  policy_name     = var.policy_name
  policy_document = var.policy_document
  attach_roles    = var.attach_roles
  attach_users    = var.attach_users

  reader_name        = var.reader_name
  reader_policy_type = var.reader_policy_type
  reader_policy_name = var.reader_policy_name
}

provider "alicloud" {
  alias = "rd_role"
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

# Create policy
resource "alicloud_ram_policy" "policy" {
  provider        = alicloud.rd_role
  policy_name     = local.policy_name
  policy_document = local.policy_document
  description     = "create by Terraform"
  force           = true
}

# Add policy to user
resource "alicloud_ram_user_policy_attachment" "user_attach" {
  provider    = alicloud.rd_role
  for_each    = toset(local.attach_users)
  policy_name = alicloud_ram_policy.policy.name
  policy_type = alicloud_ram_policy.policy.type
  user_name   = each.value
}

# Add policy to role
resource "alicloud_ram_role_policy_attachment" "role_attach" {
  provider    = alicloud.rd_role
  for_each    = toset(local.attach_roles)
  policy_name = alicloud_ram_policy.policy.name
  policy_type = alicloud_ram_policy.policy.type
  role_name   = each.value
}

# Add policy to reader role
resource "alicloud_ram_role_policy_attachment" "reader_attach" {
  provider    = alicloud.rd_role
  policy_name = local.reader_policy_name
  policy_type = local.reader_policy_type
  role_name   = local.reader_name
}
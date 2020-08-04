provider "alicloud" {
  profile = "default"
}

#create custom authority policy for system admin
resource "alicloud_ram_policy" "system_admin_policy" {
  name        = "SystemAdministratorAccess"
  document    = <<EOF
  {
    "Statement": [
        {
            "Effect": "Allow",
            "NotAction":
                [
                    "ram:*",
                    "ims:*",
                    "resourcemanager:*",
                    "bss:*",
                    "bssapi:*",
                    "efc:*"
                ],
            "Resource": "*"
        },
        {
            "Effect": "Allow",
            "Action":
                [
                    "ram:GetRole",
                    "ram:ListRoles",
                    "ram:CreateServiceLinkedRole",
                    "ram:DeleteServiceLinkedRole",
                    "bss:DescribeOrderList",
                    "bss:DescribeOrderDetail",
                    "bss:PayOrder",
                    "bss:CancelOrder"
                ],
            "Resource": "*"
        }
    ],
    "Version": "1"
}
  EOF
  description = local.language_obj.system_admin_authority
  force       = true
}
#set RAM user password strength
resource "alicloud_ram_account_password_policy" "password_policy" {
  minimum_password_length      = 8
  require_lowercase_characters = true
  require_uppercase_characters = true
  require_numbers              = true
  require_symbols              = true
  hard_expiry                  = false
  max_password_age             = 90
  password_reuse_prevention    = 8
  max_login_attempts           = 5
}
# create cloud admin group
resource "alicloud_ram_group" "cloud_admin_group" {
  name     = "CloudAdminGroup"
  comments = local.language_obj.admin_group_comments
  force    = true
}
# authorize policy to cloud admin
resource "alicloud_ram_group_policy_attachment" "cloud_admin_group_policy_attachment" {
  policy_name = "AdministratorAccess"
  policy_type = "System"
  group_name  = alicloud_ram_group.cloud_admin_group.name
}
# create system admin group
resource "alicloud_ram_group" "system_admin_group" {
  name     = "SystemAdminGroup"
  comments = local.language_obj.system_admin_comments
  force    = true
}
# authorize policy to system admin group
resource "alicloud_ram_group_policy_attachment" "system_admin_group_policy_attachment" {
  policy_name = alicloud_ram_policy.system_admin_policy.name
  policy_type = alicloud_ram_policy.system_admin_policy.type
  group_name  = alicloud_ram_group.system_admin_group.name
}
# create billing admin group 
resource "alicloud_ram_group" "billing_admin_group" {
  name     = "BillingAdminGroup"
  comments =  local.language_obj.billing_admin_comments
  force    = true
}
# authorize AliyunBSSFullAccess policy to billing admin group
resource "alicloud_ram_group_policy_attachment" "bss_group_policy_attachment_AliyunBSSFullAccess" {
  policy_name = "AliyunBSSFullAccess"
  policy_type = "System"
  group_name  = alicloud_ram_group.billing_admin_group.name
}
# authorize AliyunFinanceConsoleFullAccess policy to billing admin group
resource "alicloud_ram_group_policy_attachment" "bss_group_policy_attachment_AliyunFinanceConsoleFullAccess" {
  policy_name = "AliyunFinanceConsoleFullAccess"
  policy_type = "System"
  group_name  = alicloud_ram_group.billing_admin_group.name
}
# create common user group
resource "alicloud_ram_group" "common_user_group" {
  name     =  "CommonUserGroup"
  comments = local.language_obj.common_user_comments
  force    = true
}

locals{
    language = var.language
}

locals {
  language_obj = {
    "admin_group_comments" = local.language =="EN"?"cloud admin group":"云管理员组"
    "system_admin_comments" = local.language =="EN"?"system admin group":"系统管理员组"
    "common_user_comments" = local.language =="EN"?"common user group":"普通用户组"
    "billing_admin_comments" = local.language =="EN"?"billing admin group":"财务账单管理员组"
    "system_admin_authority" = local.language =="EN"?"system admin authority":"系统管理员权限"
  }
}
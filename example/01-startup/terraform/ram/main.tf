provider "alicloud" {
  profile = "default"
}

# 为系统管理员创建自定义权限策略
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
  description = "系统管理员权限"
  force       = true
}
# 设置RAM用户密码强度
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
# 创建云管理员组
resource "alicloud_ram_group" "cloud_admin_group" {
  name     = "CloudAdminGroup"
  comments = "云管理员组"
  force    = true
}
# 为云管理员组授权
resource "alicloud_ram_group_policy_attachment" "cloud_admin_group_policy_attachment" {
  policy_name = "AdministratorAccess"
  policy_type = "System"
  group_name  = alicloud_ram_group.cloud_admin_group.name
}
# 创建系统管理员组
resource "alicloud_ram_group" "system_admin_group" {
  name     = "SystemAdminGroup"
  comments = "系统管理员组"
  force    = true
}
# 为系统管理员组授权
resource "alicloud_ram_group_policy_attachment" "system_admin_group_policy_attachment" {
  policy_name = alicloud_ram_policy.system_admin_policy.name
  policy_type = alicloud_ram_policy.system_admin_policy.type
  group_name  = alicloud_ram_group.system_admin_group.name
}
# 创建财务账单管理员组
resource "alicloud_ram_group" "billing_admin_group" {
  name     = "BillingAdminGroup"
  comments = "财务账单管理员组"
  force    = true
}
# 为财务账单管理员组授权AliyunBSSFullAccess
resource "alicloud_ram_group_policy_attachment" "bss_group_policy_attachment_AliyunBSSFullAccess" {
  policy_name = "AliyunBSSFullAccess"
  policy_type = "System"
  group_name  = alicloud_ram_group.billing_admin_group.name
}
# 为财务账单管理员组授权AliyunFinanceConsoleFullAccess
resource "alicloud_ram_group_policy_attachment" "bss_group_policy_attachment_AliyunFinanceConsoleFullAccess" {
  policy_name = "AliyunFinanceConsoleFullAccess"
  policy_type = "System"
  group_name  = alicloud_ram_group.billing_admin_group.name
}
# 创建普通用户组
resource "alicloud_ram_group" "common_user_group" {
  name     = "CommonUserGroup"
  comments = "普通用户组"
  force    = true
}
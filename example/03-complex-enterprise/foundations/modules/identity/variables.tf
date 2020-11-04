# SharedServices 账号的 UID
variable "shared_services_account_id" {
  default = ""
}

# Bussiness 目录 ID
variable "business_folder_id" {
  default = ""
}

# 附加到成员账号ram user的权限策略
variable "attached_system_policy" {
  default = "AdministratorAccess"
}

# 成员账号ram user的名称
variable "ram_user_name" {
  default = "LandingZoneAccountSetup"
}

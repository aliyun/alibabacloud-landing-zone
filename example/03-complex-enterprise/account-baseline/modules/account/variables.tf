variable "app_setting" {
  description = ""
}

variable "cen_instance_id" {}

variable "folder_id" {}

variable "payer_account_id" {}

variable "vpc_production_id" {}

variable "vpc_non_production_id" {}

variable "vswitches_shared_services" {}

variable "vswitches_dmz" {}

# variable "network_acl_enabled" {}

# 附加到成员账号ram user的权限策略
variable "attached_system_policy" {
  default = "AdministratorAccess"
}

# 成员账号ram user的名称
variable "ram_user_name" {
  default = "LandingZoneAccountSetup"
}
#####################################################################
########################   三号样板间基础配置   ########################
#####################################################################

# Terraform Backend 配置
terraform {
  backend "oss" {}
}

# 配置 AK、Region 等信息
provider "alicloud" {
  access_key = var.access_key
  secret_key = var.secret_key
  region     = var.region
}

# assumerole到SharedServices账号
provider "alicloud" {
  alias      = "sharedservices"
  access_key = var.access_key
  secret_key = var.secret_key
  region     = var.region
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", alicloud_resource_manager_account.rd_account_SharedServices.id)
    session_name       = "SharedAccountLandingZoneSetup"
    session_expiration = 999
  }
}


# 获取当前主账号的信息
data "alicloud_account" "current_account" {
}

######################## 步骤一 [企业管理账号创建和初始化]################
# 手工操作,需要增加发票信息,否则主账号不能被选为费用结算账号



######################## 步骤二 [云账号安全加固]########################
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

# # 创建用户: admin
# resource "alicloud_ram_user" "user_admin" {
#   name         = var.basic_settings.admin_sub_account_name
#   display_name = "管理员"
# }

# # 将admin加入云管理员组
# resource "alicloud_ram_group_membership" "membership_admin" {
#   group_name = alicloud_ram_group.cloud_admin_group.name
#   user_names = [alicloud_ram_user.user_admin.name]
# }

# 密码策略
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



######################## 步骤三 [资源结构和账号创建] ####################

# 创建资源夹 Core
resource "alicloud_resource_manager_folder" "rd_folder_core" {
  folder_name = var.basic_settings.resource_directory.core_directory_name
} 

# 创建资源夹 Business ，作为以后放置业务相关账号的资源夹
resource "alicloud_resource_manager_folder" "rd_folder_Business" {
  folder_name = var.basic_settings.resource_directory.applications_directory_name
}

# 创建一个资源账号:SharedServices, 结算账号选择资源目录主账号
resource "alicloud_resource_manager_account" "rd_account_SharedServices" {
  display_name = var.basic_settings.shared_services_account_name
  folder_id = alicloud_resource_manager_folder.rd_folder_core.id
  payer_account_id = data.alicloud_account.current_account.id
}

######################## 步骤三 [IT合规与审计] #########################

module "governance" {
  source = "./modules/governance"

  providers = {
    alicloud = alicloud
  }

  bucket_audit_logs = var.basic_settings.governance.bucket_enterprise_audit_logs
  trail_audit_logs = var.basic_settings.governance.trail_enterprise_audit_logs
  mns = var.basic_settings.governance.mns
  master_id = data.alicloud_account.current_account.id
}

######################## 步骤四 [企业管理账号身份集成] ###################

module "identity" {
  source = "./modules/identity"
  providers = {
    alicloud = alicloud
    alicloud.sharedservices = alicloud.sharedservices
  }
  shared_services_account_id = alicloud_resource_manager_account.rd_account_SharedServices.id
  business_folder_id = alicloud_resource_manager_folder.rd_folder_Business.id
}

######################## 步骤五 [网络配置] #############################
module "networking" {
  source = "./modules/networking"
  providers = {
    alicloud = alicloud
    alicloud.sharedservices = alicloud.sharedservices
  }
  network_settings = var.network_settings
  region = var.region
}
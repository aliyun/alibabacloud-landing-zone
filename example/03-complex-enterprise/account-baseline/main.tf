#####################################################################
########################   三号样板间账号基线   ########################
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
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", var.foundations.shared_services_uid)
    session_name       = "SharedAccountLandingZoneSetup"
    session_expiration = 999
  }
}

######################## 初始化 App 成员账号 #######################
module "app" {
  source = "./modules/account"

  providers = {
    alicloud = alicloud
    alicloud.sharedservices = alicloud.sharedservices
  }

  for_each = var.applications_accounts

  app_setting = each.value
  folder_id = var.foundations.rd_folder_application_id
  payer_account_id = var.foundations.master_uid
  cen_instance_id = var.foundations.networking.cen_instance_id
  vswitches_shared_services = var.foundations.networking.vswitches_shared_services
  vswitches_dmz = var.foundations.networking.vswitches_dmz
  vpc_production_id = var.foundations.networking.vpc_production_id
  vpc_non_production_id = var.foundations.networking.vpc_non_production_id
}
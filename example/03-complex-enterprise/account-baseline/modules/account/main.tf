provider "alicloud" {}
provider "alicloud" {
  alias = "sharedservices"
}

# 创建成员账号

resource "alicloud_resource_manager_account" "rd_account_app" {
  display_name = var.app_setting.account_name
  folder_id = var.folder_id
  payer_account_id = var.payer_account_id
}

# App 账号网络初始化

## Production 网络初始化
module "app_network_production" {
  source = "../network"

  providers = {
    alicloud = alicloud.sharedservices
  }

  app_network_setting = var.app_setting.networks.vpc_production
  vpc_id = var.vpc_production_id
  cen_instance_id = var.cen_instance_id
  vswitches_shared_services = var.vswitches_shared_services
  vswitches_dmz = var.vswitches_dmz
  network_acl_enabled = var.app_setting.networks.vpc_production.network_acl_enabled
}

## Non-Production 网络初始化
module "app_network_non_production" {
  source = "../network"

  providers = {
    alicloud = alicloud.sharedservices
  }

  app_network_setting = var.app_setting.networks.vpc_non_production
  vpc_id = var.vpc_non_production_id
  cen_instance_id = var.cen_instance_id
  vswitches_shared_services = var.vswitches_shared_services
  vswitches_dmz = var.vswitches_dmz
  network_acl_enabled = var.app_setting.networks.vpc_non_production.network_acl_enabled
}
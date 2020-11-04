# assumerole到SharedServices账号
provider "alicloud" {
  alias      = "sharedservices"
}


######################## 网络配置 ##################

module "network_main" {
  source = "./network"
  providers = {
    alicloud = alicloud.sharedservices
  }
  
  network_settings = var.network_settings
  region = var.region
}

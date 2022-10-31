terraform {
  required_providers {
    alicloud = {
      source = "aliyun/alicloud"
      version = "1.160.0"
    }
  }
}

provider "alicloud" {
  region = var.region
}

module "dmz_ingress_alb" {
  source                       = "../.."
  vpc_id                       = var.dmz_vpc_id
  alb_instance_deploy_config   = var.alb_instance_deploy_config
  server_group_backend_servers = var.server_group_backend_servers
}

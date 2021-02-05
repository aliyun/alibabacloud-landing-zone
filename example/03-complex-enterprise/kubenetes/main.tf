# Terraform Backend 配置
terraform {
  backend "oss" {}
}

# 配置 AK、Region 等信息
provider "alicloud" {
  access_key = var.access_key
  secret_key = var.secret_key
  security_token = var.security_token
  region     = var.region
}

# Assume role 到App1账号
provider "alicloud" {
  alias      = "app1"
  access_key = var.access_key
  secret_key = var.secret_key
  security_token = var.security_token
  region     = var.region
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", var.foundations.app1_uid)
    session_name       = "App1AccountLandingZoneSetup"
    session_expiration = 999
  }
}


module "app_k8s_identity" {
  source = "./modules/identity"
  providers = {
    alicloud=alicloud.app1
  }
} 

module "app_k8s_cluster" {
  source = "./modules/cluster"
  providers = {
    alicloud = alicloud.app1
  }
  k8s_number              = var.applications_cluster_setting.k8s_cluster.k8s_number
  k8s_name                = var.applications_cluster_setting.k8s_cluster.k8s_name
  worker_vswitch_ids      = var.applications_cluster_setting.k8s_cluster.worker_vswitch_ids
  pod_vswitch_ids         = var.applications_cluster_setting.k8s_cluster.pod_vswitch_ids
  worker_instance_types   = var.applications_cluster_setting.k8s_cluster.worker_instance_types
  worker_number           = var.applications_cluster_setting.k8s_cluster.worker_number
  install_cloud_monitor   = var.applications_cluster_setting.k8s_cluster.install_cloud_monitor
  proxy_mode              = var.applications_cluster_setting.k8s_cluster.proxy_mode
  node_login_password     = var.applications_cluster_setting.k8s_cluster.node_login_password
  service_cidr            = var.applications_cluster_setting.k8s_cluster.service_cidr
  # terway 模式不需要
  pod_cidr                = var.applications_cluster_setting.k8s_cluster.pod_cidr
  cluster_spec            = var.applications_cluster_setting.k8s_cluster.cluster_spec
  cluster_addons          = var.applications_cluster_setting.k8s_cluster.cluster_addons
  # endpoint_public_access  = false
  ##namespace               = var.applications_cluster_setting.container_images.namespace
  ##repo_name               = var.applications_cluster_setting.container_images.repo_name
} 

## NAT dnat 共享带宽创建
module "container_network" {
  source = "./modules/network"
  providers = {
    alicloud = alicloud
  }
  count = var.network_settings.network_enabled ? 1 : 0
  vpc_id = var.network_settings.vpc_id
  network_enabled  = var.network_settings.network_enabled
  eip_id        = var.network_settings.eip_id
  nat_id        = var.network_settings.nat_id
  external_port = var.network_settings.external_port
  ip_protocol = var.network_settings.ip_protocol
  internal_ip = var.network_settings.internal_ip
  internal_port = var.network_settings.internal_port
}

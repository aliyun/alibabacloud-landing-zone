provider "alicloud" {
    access_key = var.alicloud_access_key
    secret_key = var.alicloud_secret_key
    region = var.region
}

locals {
  ack_managed_cluster_name = "enterprise-ack"
}

module "ack-managed-cluster" {
  source = "./modules/ack-managed-cluster"

  # Network variables
  vpc_cidr = var.vpc_cidr
  node_vswitches = var.node_vswitches
  pod_vswitches = var.pod_vswitches
  eip_bandwidth = var.eip_bandwidth

  # ACK cluster variables
  ack_managed_cluster_name = local.ack_managed_cluster_name
  load_balancer_spec = var.load_balancer_spec
  service_cidr = var.service_cidr
  timezone = var.timezone
  ack_version = var.ack_version

  # ACK node pool variables
  desired_size = var.desired_size
  ack_key_pair_name = var.ack_key_pair_name
  worker_instance_types = var.worker_instance_types
  disk_category = var.disk_category
  system_disk_size = var.system_disk_size
  data_disk_size = var.data_disk_size
}
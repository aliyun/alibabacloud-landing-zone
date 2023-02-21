variable "pod_vsw_id" {}
variable "node_vsw_id" {}
variable "service_cidr" {}

# Kubernetes托管版。
resource "alicloud_cs_managed_kubernetes" "k8s" {
  # Kubernetes集群名称。
  name               = "cluster_demo"
  # 创建Pro版集群。
  cluster_spec       = "ack.pro.small"
  version            = "1.24.6-aliyun.1"
  # 新的Kubernetes集群将位于的vSwitch。指定一个或多个vSwitch的ID。它必须在availability_zone指定的区域中。
  worker_vswitch_ids = [var.node_vsw_id]
  pod_vswitch_ids    = [var.pod_vsw_id]
  service_cidr       = var.service_cidr
  enable_rrsa = true

  addons {
    name = "terway-eniip"
  }
}

resource "alicloud_cs_kubernetes_node_pool" "k8s_node_pool" {
  name           = "node_demo"
  cluster_id     = alicloud_cs_managed_kubernetes.k8s.id
  vswitch_ids    = [var.node_vsw_id]
  instance_types = ["ecs.g6.large"]
  image_type = "AliyunLinux"
  system_disk_category = "cloud_essd"
  system_disk_size     = 80
  password = "Ros12345"
  desired_size = 3
  runtime_name          = "containerd"
  runtime_version       = "1.5.13"
}

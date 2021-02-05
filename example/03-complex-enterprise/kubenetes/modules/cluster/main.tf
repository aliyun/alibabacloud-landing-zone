
## 容器镜像命名空间和仓库创建
# resource "alicloud_cr_namespace" "cr_namespace_k8sapp" {
#   name               = var.namespace
#   auto_create        = false
#   default_visibility = var.default_visibility
# }

# resource "alicloud_cr_repo" "cr_repo_k8sapp" {
#   namespace = alicloud_cr_namespace.cr_namespace_k8sapp.name
#   name      = var.repo_name
#   summary   = var.summary
#   repo_type = var.repo_type
# }

## k8s 集群创建
resource "alicloud_cs_managed_kubernetes" "kubernetes_cluster" {
  count                 = var.k8s_number
  name                  = var.k8s_name
  worker_vswitch_ids    = var.worker_vswitch_ids
  pod_vswitch_ids       = var.pod_vswitch_ids
  worker_instance_types = var.worker_instance_types
  worker_number         = var.worker_number
  install_cloud_monitor = var.install_cloud_monitor
  proxy_mode            = var.proxy_mode
  password              = var.node_login_password
  service_cidr          = var.service_cidr
  pod_cidr              = var.pod_cidr
  cluster_spec          = var.cluster_spec
  new_nat_gateway       = false
  version               = "1.18.8-aliyun.1"
  slb_internet_enabled  = false
  dynamic "addons" {
      for_each = var.cluster_addons
      content {
        name                    = lookup(addons.value, "name", var.cluster_addons)
        config                  = lookup(addons.value, "config", var.cluster_addons)
      }
  }
}

# 找到容器服务创建的私网 SLB
data "alicloud_slbs" "slbs" {
    name_regex  = "^([^M])"
    tags = {
      "ack.aliyun.com" = alicloud_cs_managed_kubernetes.kubernetes_cluster[0].id
    }
}


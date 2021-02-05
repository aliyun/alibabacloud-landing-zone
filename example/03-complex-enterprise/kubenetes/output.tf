output "slb_address" {
  value = module.app_k8s_cluster.slb_address.slbs.0.address
}
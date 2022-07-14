output "anti_ddos_id" {
  value = alicloud_ddoscoo_instance.newInstance.id
}

output "shared_service_account_domain_resource_id" {
  value = alicloud_ddoscoo_domain_resource.shared_service_account_domain_resource.id
}

output "dev_account_domain_resource_id" {
  value = alicloud_ddoscoo_domain_resource.dev_account_domain_resource.id
}
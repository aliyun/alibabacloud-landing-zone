output "eip_id_list" {
  description = "EIP ID."
  value       = [for idx, eip in alicloud_eip_address.eip_address : eip.id]
}

output "eip_address_list" {
  description = "EIP Address."
  value       = [for idx, eip in alicloud_eip_address.eip_address : eip.ip_address]
}

output "common_bandwidth_package_id" {
  description = "Common bandwidth package ID."
  value = var.create_common_bandwidth_package ? alicloud_common_bandwidth_package.bandwidth_package.0.id : ""
}

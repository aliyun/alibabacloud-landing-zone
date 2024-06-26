output "eip_id_list" {
  description = "EIP ID."
  value       = module.dmz_egress_eip.eip_id_list
}

output "eip_address_list" {
  description = "EIP Address."
  value       = module.dmz_egress_eip.eip_address_list
}

output "common_bandwidth_package_id" {
  description = "Common bandwidth package ID."
  value       = module.dmz_egress_eip.common_bandwidth_package_id
}
output "egress_nat_gateway_id" {
  description = "The ID of the nat gateway."
  value       = alicloud_nat_gateway.nat_gateway_dmz.id
}

output "egress_eip_id" {
  description = "The resource ID in terraform of Address."
  value       = alicloud_eip_address.eip_dmz_egress.id
}

output "egress_eip_ip_address" {
  description = "The address of the EIP."
  value       = alicloud_eip_address.eip_dmz_egress.ip_address
}

output "egress_snat_entry_id" {
  description = "The ID of the snat entry. The value formats as <snat_table_id>:<snat_entry_id>"
  value       = alicloud_snat_entry.snat_entry.id
}
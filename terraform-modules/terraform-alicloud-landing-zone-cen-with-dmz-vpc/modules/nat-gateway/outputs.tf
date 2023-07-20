output "nat_gateway_id" {
  description = "The ID of the nat gateway."
  value       = alicloud_nat_gateway.nat_gateway.id
}

output "nat_gateway_snat_entry_id_list" {
  description = "The ID list of SNAT entries."
  value = [for idx, entry in alicloud_snat_entry.snat_entry : entry.id]
}
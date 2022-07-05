output "egress_eip_id" {
  value = module.shared_service_account_dmz.egress_eip_id
}

output "egress_eip_ip_address" {
  value = module.shared_service_account_dmz.egress_eip_ip_address
}

output "egress_nat_gateway_id" {
  value = module.shared_service_account_dmz.egress_nat_gateway_id
}

output "egress_snat_entry_id" {
  value = module.shared_service_account_dmz.egress_snat_entry_id
}

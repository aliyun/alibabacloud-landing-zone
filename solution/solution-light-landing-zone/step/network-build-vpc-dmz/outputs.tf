output "egress_eip_id" {
  value = module.daily_account_dmz_eip.eip_id_list
}

output "egress_eip_ip_address" {
  value = module.daily_account_dmz_eip.eip_address_list
}

output "egress_nat_gateway_id" {
  value = module.dev_account_dmz_nat_gateway.nat_gateway_id
}

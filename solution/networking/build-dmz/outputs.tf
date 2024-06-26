output "egress_eip_id" {
  value = module.dmz_egress_eip.eip_id_list
}

output "egress_eip_ip_address" {
  value = module.dmz_egress_eip.eip_address_list
}

output "egress_nat_gateway_id" {
  value = module.dmz_egress_nat_gateway.nat_gateway_id
}

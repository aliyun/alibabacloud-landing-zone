output "nat_gateway_id" {
  description = "ID of the nat gateway."
  value       = module.dmz_egress_nat_gateway.nat_gateway_id
}

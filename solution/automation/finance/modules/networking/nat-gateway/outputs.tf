output "nat_gateway_id" {
  description = "The ID of the nat gateway."
  value       = alicloud_nat_gateway.nat_gateway.id
}

# outputs.tf https://learn.hashicorp.com/tutorials/terraform/outputs
output "route_table_id" {
  description = "The ID of the transit router table."
  value       = var.create_route_table ? alicloud_cen_transit_router_route_table.custom_route_table.0.transit_router_route_table_id : ""
}

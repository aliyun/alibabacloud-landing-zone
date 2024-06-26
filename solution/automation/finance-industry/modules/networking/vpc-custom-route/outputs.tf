# outputs.tf https://learn.hashicorp.com/tutorials/terraform/outputs
output "route_table_id" {
  value = var.create_route_table ? alicloud_route_table.vpc_route_table.0.id : data.alicloud_route_tables.vpc_route_tables.ids.0
}

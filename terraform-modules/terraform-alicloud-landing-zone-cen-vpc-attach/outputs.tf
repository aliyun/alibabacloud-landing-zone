output "id" {
  description = "ID of the resource, It is formatted to <transit_router_id>:<transit_router_attachment_id>"
  value       = alicloud_cen_transit_router_vpc_attachment.vpc_attachment.id
}

output "attachment_id" {
  description = "ID of transit router attachment."
  value       = alicloud_cen_transit_router_vpc_attachment.vpc_attachment.transit_router_attachment_id
}

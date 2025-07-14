resource "alicloud_cen_transit_router_route_table" "custom_route_table" {
  transit_router_id               = var.transit_router_id
  transit_router_route_table_name = var.transit_router_route_table_name
}

resource "alicloud_cen_transit_router_route_table_association" "custom_route_table_association" {
  count                         = length(var.transit_router_association_attachment_ids)
  transit_router_route_table_id = alicloud_cen_transit_router_route_table.custom_route_table.transit_router_route_table_id
  transit_router_attachment_id  = var.transit_router_association_attachment_ids[count.index]
}

resource "alicloud_cen_transit_router_route_entry" "custom_route_table_entry" {
  count                                             = length(var.transit_router_route_entry_config)
  transit_router_route_table_id                     = alicloud_cen_transit_router_route_table.custom_route_table.transit_router_route_table_id
  transit_router_route_entry_destination_cidr_block = var.transit_router_route_entry_config[count.index].route_entry_dest_cidr
  transit_router_route_entry_next_hop_type          = var.transit_router_route_entry_config[count.index].route_entry_next_hop_type
  transit_router_route_entry_name                   = var.transit_router_route_entry_config[count.index].route_entry_name
  transit_router_route_entry_description            = var.transit_router_route_entry_config[count.index].route_entry_description
  transit_router_route_entry_next_hop_id            = var.transit_router_route_entry_config[count.index].route_entry_next_hop_id
}
data "alicloud_route_tables" "vpc_route_tables" {
  vpc_id = var.vpc_id
}

resource "alicloud_route_entry" "dev_account_vpc_route_entry" {
  count                 = length(var.route_entry_config)
  route_table_id        = data.alicloud_route_tables.vpc_route_tables.ids.0
  destination_cidrblock = var.route_entry_config[count.index].destination_cidrblock
  nexthop_type          = var.route_entry_config[count.index].nexthop_type
  nexthop_id            = var.route_entry_config[count.index].nexthop_id
}
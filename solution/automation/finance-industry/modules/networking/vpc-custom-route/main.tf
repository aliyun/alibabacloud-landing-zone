resource "alicloud_route_table" "vpc_route_table" {
  count            = var.create_route_table ? 1 : 0
  vpc_id           = var.vpc_id
  route_table_name = var.route_table_name
  description      = var.route_table_description
}

resource "alicloud_route_table_attachment" "vpc_route_table_attachment" {
  count          = var.create_route_table ? 1 : 0
  vswitch_id     = var.vswitch_id
  route_table_id = alicloud_route_table.vpc_route_table.0.id
}

data "alicloud_route_tables" "vpc_route_tables" {
  vpc_id = var.vpc_id
}

resource "alicloud_route_entry" "vpc_route_entry" {
  count                 = length(var.route_entry_config)
  route_table_id        = var.create_route_table ? alicloud_route_table.vpc_route_table.0.id : data.alicloud_route_tables.vpc_route_tables.ids.0
  destination_cidrblock = var.route_entry_config[count.index].destination_cidrblock
  nexthop_type          = var.route_entry_config[count.index].nexthop_type
  nexthop_id            = var.route_entry_config[count.index].nexthop_id
}
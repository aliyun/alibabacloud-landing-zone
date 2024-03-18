resource "alicloud_nat_gateway" "nat_gateway" {
  nat_type         = "Enhanced"
  vpc_id           = var.vpc_id
  nat_gateway_name = var.name
  vswitch_id       = var.vswitch_id
  network_type     = var.network_type
  payment_type     = var.payment_type
  period           = var.period
  tags             = var.tags
}

resource "alicloud_eip_association" "eip_association" {
  count = length(var.association_eip_id_list)

  allocation_id = var.association_eip_id_list[count.index]
  instance_id   = alicloud_nat_gateway.nat_gateway.id
}

resource "alicloud_snat_entry" "snat_entry" {
  count = length(var.snat_source_cidr_list)

  snat_ip       = join(",", var.snat_ip_list)
  source_cidr   = var.snat_source_cidr_list[count.index]
  snat_table_id = alicloud_nat_gateway.nat_gateway.snat_table_ids
}


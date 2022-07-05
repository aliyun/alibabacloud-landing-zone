resource "alicloud_nat_gateway" "nat_gateway_dmz" {
  nat_type         = "Enhanced"
  vpc_id           = var.vpc_id
  nat_gateway_name = var.nat_gateway_name
  vswitch_id       = var.nat_gateway_vswitch_id
}

resource "alicloud_eip_address" "eip_dmz_egress" {
  address_name = var.eip_address_name
}

resource "alicloud_eip_association" "eip_association" {
  instance_id   = alicloud_nat_gateway.nat_gateway_dmz.id
  allocation_id = alicloud_eip_address.eip_dmz_egress.id
}

resource "alicloud_snat_entry" "snat_entry" {
  source_cidr   = var.all_vpc_cidr
  snat_table_id = alicloud_nat_gateway.nat_gateway_dmz.snat_table_ids
  snat_ip       = alicloud_eip_address.eip_dmz_egress.ip_address

  depends_on    = [alicloud_eip_association.eip_association]
}


//dmz vpc上的nat网关查询
data "alicloud_nat_gateways" "nat_dmz" {
  ids        = [var.nat_id]
}

//混合云eip
data "alicloud_eips" "eips_ds" {
    ids = [var.eip_id]
}

resource "alicloud_eip_association" "eip_association_nat" {
  count         = var.network_enabled ? 1 : 0
  allocation_id = var.eip_id
  instance_id   = data.alicloud_nat_gateways.nat_dmz.ids.0
}

resource "alicloud_forward_entry" "default" {
  count            = var.network_enabled ? 1 : 0
  forward_table_id = data.alicloud_nat_gateways.nat_dmz.gateways.0.forward_table_id
  external_ip      = data.alicloud_eips.eips_ds.eips.0.ip_address //混合云eip
  external_port    = var.external_port
  ip_protocol      = var.ip_protocol
  internal_ip      = var.internal_ip
  internal_port    = var.internal_port
} 
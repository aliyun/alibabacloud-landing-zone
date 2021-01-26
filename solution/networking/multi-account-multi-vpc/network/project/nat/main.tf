//创建nat,eip,关联eip到nat
resource "alicloud_nat_gateway" "nat_gateway" {
  vpc_id               = var.vpc_id
  specification        = var.nat.specification
  name                 = var.nat.natgateway_name
  vswitch_id           = var.vswitch_id
  nat_type             = var.nat.nat_type
}

resource "alicloud_eip" "eip" {
  bandwidth            = var.nat.eip_bandwidth
  internet_charge_type = var.nat.eip_internet_charge_type
  tags                 = var.nat.eip_tags
}

resource "alicloud_eip_association" "eip_association_nat" {
  allocation_id = alicloud_eip.eip.id
  instance_id   = alicloud_nat_gateway.nat_gateway.id
}

# 发布nat自定义路由到云企业网
data "alicloud_route_tables" "vpc_route_table_ds" {
  vpc_id = var.vpc_id
}

resource "alicloud_cen_route_entry" "cen_nat_route_entry" {
  instance_id    = var.cen_id
  route_table_id = data.alicloud_route_tables.vpc_route_table_ds.ids[0]
  cidr_block     = "0.0.0.0/0"
  depends_on     = [alicloud_nat_gateway.nat_gateway]
}


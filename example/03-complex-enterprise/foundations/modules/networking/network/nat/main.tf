/**************************创建NAT,eip,共享带宽******************************/
resource "alicloud_nat_gateway" "nat_gateway" {
  vpc_id = var.vpc_id
  name   = var.nat_name
}

resource "alicloud_eip" "alicloud_eip_demo" {
  bandwidth            = var.eip_bandwidth
  internet_charge_type = var.eip_internet_charge_type
}

resource "alicloud_common_bandwidth_package" "common_bandwidth_package" {
  count                = var.common_bandwidth_package_enabled ? 1 : 0
  name                 = var.common_bandwidth_package_name
  bandwidth            = var.common_bandwidth_package_bandwidth
  internet_charge_type = var.common_bandwidth_package_internet_charge_type
}

resource "alicloud_common_bandwidth_package_attachment" "common_bandwidth_package_attachment" {
  count                = var.common_bandwidth_package_enabled ? 1 : 0
  bandwidth_package_id = alicloud_common_bandwidth_package.common_bandwidth_package.0.id
  instance_id          = alicloud_eip.alicloud_eip_demo.id
}

resource "alicloud_eip_association" "eip_association_nat" {
  allocation_id = alicloud_eip.alicloud_eip_demo.id
  instance_id   = alicloud_nat_gateway.nat_gateway.id
}


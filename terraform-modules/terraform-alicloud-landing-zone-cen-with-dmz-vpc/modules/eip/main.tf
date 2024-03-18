resource "alicloud_eip_address" "eip_address" {
  count = length(var.eip_config)

  address_name = var.eip_config[count.index].eip_address_name
  payment_type = var.eip_config[count.index].payment_type
  period       = var.eip_config[count.index].period
  tags         = var.eip_config[count.index].tags
}

resource "alicloud_eip_association" "eip_association" {
  count = var.eip_association_instance_id != "" ? length(alicloud_eip_address.eip_address) : 0

  allocation_id = alicloud_eip_address.eip_address[count.index].id
  instance_id   = var.eip_association_instance_id
}

resource "alicloud_common_bandwidth_package" "bandwidth_package" {
  count = var.create_common_bandwidth_package ? 1 : 0

  bandwidth              = var.common_bandwidth_package_bandwidth
  internet_charge_type   = var.common_bandwidth_package_internet_charge_type
  bandwidth_package_name = var.common_bandwidth_package_name
}

resource "alicloud_common_bandwidth_package_attachment" "bandwidth_package_attachment" {
  count = var.create_common_bandwidth_package ? length(alicloud_eip_address.eip_address) : 0

  bandwidth_package_id = alicloud_common_bandwidth_package.bandwidth_package.0.id
  instance_id          = alicloud_eip_address.eip_address[count.index].id
}


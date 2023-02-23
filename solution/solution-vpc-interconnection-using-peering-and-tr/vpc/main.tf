variable "vpc_cidr" {}
variable "vsw_cidr" {}
variable "zone_id" {}

resource "alicloud_vpc" "vpc" {
  vpc_name = "vpc_test"
  cidr_block = var.vpc_cidr
}

resource "alicloud_vswitch" "vsw" {
  vpc_id = alicloud_vpc.vpc.id
  cidr_block = var.vsw_cidr
  zone_id = var.zone_id
}

output "vpc_id" {
  value = alicloud_vpc.vpc.id
}
output "vsw_id" {
  value = alicloud_vswitch.vsw.id
}
output "route_table_id" {
  value = alicloud_vpc.vpc.route_table_id
}

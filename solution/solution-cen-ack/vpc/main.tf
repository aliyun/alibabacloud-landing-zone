variable "vpc_cidr" {}
variable "pod_vsw_cidr" {}
variable "node_vsw_cidr" {}
variable "zone_id" {}


resource "alicloud_vpc" "vpc" {
  vpc_name = "vpc_test"
  cidr_block = var.vpc_cidr
}

resource "alicloud_vswitch" "pod_vsw" {
  vpc_id = alicloud_vpc.vpc.id
  cidr_block = var.pod_vsw_cidr
  zone_id = var.zone_id
}

resource "alicloud_vswitch" "node_vsw" {
  vpc_id = alicloud_vpc.vpc.id
  cidr_block = var.node_vsw_cidr
  zone_id = var.zone_id
}

output "vpc_id" {
  value = alicloud_vpc.vpc.id
}
output "pod_vsw_id" {
  value = alicloud_vswitch.pod_vsw.id
}
output "node_vsw_id" {
  value = alicloud_vswitch.node_vsw.id
}
output "route_table_id" {
  value = alicloud_vpc.vpc.route_table_id
}

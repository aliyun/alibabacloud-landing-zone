output "vpc_id" {
  value = alicloud_vpc.vpc.id
}

output "vswitch_id" {
  value = alicloud_vswitch.vsw.id
}

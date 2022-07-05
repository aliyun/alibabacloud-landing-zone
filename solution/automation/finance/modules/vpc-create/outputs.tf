# outputs.tf https://learn.hashicorp.com/tutorials/terraform/outputs
output "vpc_id" {
  description = "vpc id"
  value       = concat(alicloud_vpc.vpc.*.id, [""])[0]
}

output "vsw1_id" {
  description = "vswitch id"
  value = length(var.vswitch_configuration) >=1 ? alicloud_vswitch.vswitches[var.vswitch_configuration.0.vswitch_name].id : ""
}

output "vsw2_id" {
  description = "vswitch id"
  value = length(var.vswitch_configuration) >=2 ? alicloud_vswitch.vswitches[var.vswitch_configuration.1.vswitch_name].id : ""
}
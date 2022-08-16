# outputs.tf https://learn.hashicorp.com/tutorials/terraform/outputs
output "vpc_id" {
  description = "VPC ID"
  value       = concat(alicloud_vpc.vpc.*.id, [""])[0]
}

output "vsw1_id" {
  description = "VSwitch ID"
  value = length(var.vswitch_configuration) >=1 ? alicloud_vswitch.vswitches[var.vswitch_configuration.0.vswitch_name].id : ""
}

output "vsw2_id" {
  description = "VSwitch ID"
  value = length(var.vswitch_configuration) >=2 ? alicloud_vswitch.vswitches[var.vswitch_configuration.1.vswitch_name].id : ""
}

output "vsw3_id" {
  description = "VSwitch ID"
  value = length(var.vswitch_configuration) >=3 ? alicloud_vswitch.vswitches[var.vswitch_configuration.2.vswitch_name].id : ""
}

output "vsw4_id" {
  description = "VSwitch ID"
  value = length(var.vswitch_configuration) >=4 ? alicloud_vswitch.vswitches[var.vswitch_configuration.3.vswitch_name].id : ""
}
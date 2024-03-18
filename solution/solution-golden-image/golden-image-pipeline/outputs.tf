output "vpcId" {
  value = alicloud_vpc.immediate_instance.id
}

output "vswitchId" {
  value = alicloud_vswitch.immediate_instance.id
}

output "securityGroupId" {
  value = alicloud_security_group.immediate_instance.id
}

output "goldenImageAutomationTemplateId" {
  value = alicloud_oos_template.golden_image_automation.template_id
}

output "goldenImageAutomationTemplate" {
  value = alicloud_oos_template.golden_image_automation.id
}

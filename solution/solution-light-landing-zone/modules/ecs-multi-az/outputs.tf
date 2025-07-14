output "security_group_id" {
  description = "The ID of the security group."
  value       = alicloud_security_group.group.id
}

output "ecs_instance_ids" {
  description = "The ID of the ecs instance."
  value       = [for idx, inst in alicloud_instance.instances: inst.id]
}



output "alb_instance_id" {
  description = "The ID of the ALB instance."
  value       = alicloud_alb_load_balancer.default.id
}

output "server_group_id" {
  description = "The ID of the server group."
  value       = alicloud_alb_server_group.default.id
}

output "alb_listener_id" {
  description = "The ID of the ALB Listener."
  value       = alicloud_alb_listener.default.id
}



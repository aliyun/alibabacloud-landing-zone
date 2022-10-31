output "alb_instance_id" {
  description = "The ID of the ALB instance."
  value       = module.dmz_ingress_alb.alb_instance_id
}

output "server_group_id" {
  description = "The ID of the server group."
  value       = module.dmz_ingress_alb.server_group_id
}

output "alb_listener_id" {
  description = "The ID of the ALB Listener."
  value       = module.dmz_ingress_alb.alb_listener_id
}
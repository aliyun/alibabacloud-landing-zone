output "prod_account_security_group_id" {
  description = "The ID of the security group."
  value       = module.prod_account_ecs_alb.security_group_id
}

output "prod_account_ecs_instance_ids" {
  description = "The ID of the ecs instance."
  value       = module.prod_account_ecs_alb.ecs_instance_ids
}

output "prod_account_alb_instance_id" {
  description = "The ID of ALB instance."
  value       = module.prod_account_ecs_alb.alb_instance_id
}

output "prod_account_server_group_id" {
  description = "The ID of the server group."
  value       = module.prod_account_ecs_alb.server_group_id
}

output "prod_account_alb_listener_id" {
  description = "The ID of the ALB Listener."
  value       = module.prod_account_ecs_alb.alb_listener_id
}

output "dev_account_security_group_id" {
  description = "The ID of the security group."
  value       = module.dev_account_ecs_alb.security_group_id
}

output "dev_account_ecs_instance_id_ip" {
  description = "The ID of the ecs instance."
  value       = module.dev_account_ecs_alb.ecs_instance_ids
}

output "dev_account_alb_instance_id" {
  description = "The ID of ALB instance."
  value       = module.dev_account_ecs_alb.alb_instance_id
}

output "dev_account_server_group_id" {
  description = "The ID of the server group."
  value       = module.dev_account_ecs_alb.server_group_id
}

output "dev_account_alb_listener_id" {
  description = "The ID of the ALB Listener."
  value       = module.dev_account_ecs_alb.alb_listener_id
}
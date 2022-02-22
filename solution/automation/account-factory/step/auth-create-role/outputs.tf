output "role_arn" {
  value      = {
  for role in var.ram_roles.roles :role.role_name => module.ram_role[role.role_name].role_arn
  }
  depends_on = [module.ram_role]
}

#output "role_arn" {
#  value = [
#  for role in var.ram_roles.roles:  module.ram_role[role.role_name].role_arn
#  ]
#  depends_on = [module.ram_role]
#}
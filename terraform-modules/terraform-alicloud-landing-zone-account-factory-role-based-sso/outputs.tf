output "saml_provider_arn" {
  value = alicloud_ram_saml_provider.idp.arn
}

output "ram_roles_arn" {
  value = [
    for k, role in module.role: role.role_arn
  ]
}
resource "alicloud_ram_policy" "policy" {
  policy_name     = var.policy_name
  policy_document = var.policy_document
}

resource "alicloud_ram_role" "role" {
  name     = var.role_name
  document = var.role_document
}

resource "alicloud_ram_role_policy_attachment" "attachment" {
  policy_name = alicloud_ram_policy.policy.name
  policy_type = alicloud_ram_policy.policy.type
  role_name   = alicloud_ram_role.role.name

  depends_on = [
    alicloud_ram_policy.policy,
    alicloud_ram_role.role
  ]
}

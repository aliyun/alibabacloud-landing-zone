resource "alicloud_resource_manager_control_policy" "policy" {
  control_policy_name = var.name
  description         = var.description
  effect_scope        = "RAM"
  policy_document     = var.policy_document
}

resource "alicloud_resource_manager_control_policy_attachment" "attachment" {
  policy_id = alicloud_resource_manager_control_policy.policy.id
  target_id = var.target_id
}

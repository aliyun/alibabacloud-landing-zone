data "alicloud_cloud_sso_directories" "this" {
  name_regex = var.directory_name
}

# cloudsso directory
resource "alicloud_cloud_sso_directory" "default" {
  directory_name = var.directory_name
  mfa_authentication_status = var.mfa_authentication_status
  scim_synchronization_status = var.scim_synchronization_status
}

resource "alicloud_cloud_sso_access_configuration" "default" {
  for_each = {for access in var.permission_policies: access.access_configuration_name => access}

  access_configuration_name = each.value.access_configuration_name
  directory_id = alicloud_cloud_sso_directory.default.id

  dynamic "permission_policies" {
    for_each = each.value.permission_policy_list
    content {
      permission_policy_document = permission_policies.value.policy_document
      permission_policy_type = permission_policies.value.policy_type
      permission_policy_name = permission_policies.value.policy_name
    }
  }

  timeouts {
    create = "15m"
    update = "15m"
    delete = "30m"
  }
  relay_state = var.relay_state
  session_duration = var.session_duration
}


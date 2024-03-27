resource "alicloud_log_resource_record" "user" {
  for_each = {
    for user in var.users : user.id => user
  }
  provider      = alicloud.log_resource_record
  resource_name = "sls.common.user"
  record_id     = each.key
  tag           = each.value.name
  value         = <<EOF
  {
    "user_id": "${each.key}",
    "user_name": "${each.value.name}",
    "email": [
      "${each.value.email}"
    ],
    "enabled": true
  }
  EOF
}

locals {
  user_ids = [
    for user in var.users : user.id
  ]
}

resource "alicloud_log_resource_record" "user_group" {
  provider      = alicloud.log_resource_record
  resource_name = "sls.common.user_group"
  record_id     = var.user_group_id
  tag           = var.user_group_name
  value         = <<EOF
  {
    "user_group_id": "${var.user_group_id}",
    "user_group_name": "${var.user_group_name}",
    "enabled": true,
    "members": ${jsonencode(local.user_ids)}
  }
  EOF

  depends_on = [alicloud_log_resource_record.user]
}

resource "alicloud_log_resource_record" "action_policy" {
  provider      = alicloud.log_resource_record
  resource_name = "sls.alert.action_policy"
  record_id     = var.action_policy_id
  tag           = var.action_policy_name
  value         = <<EOF
  {
    "action_policy_id": "${var.action_policy_id}",
    "action_policy_name": "${var.action_policy_name}",
    "labels": {},
    "is_default": false,
    "primary_policy_script": "fire(type=\"email\", groups=[\"${var.user_group_id}\"], template_id=\"${var.lang == "en-US" ? "sls.app.actiontrail.builtin.en" : "sls.app.actiontrail.builtin.cn"}\", period=\"${var.notification_period}\")"
  }
  EOF

  depends_on = [alicloud_log_resource_record.user_group]
}

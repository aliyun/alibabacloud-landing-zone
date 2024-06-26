resource "alicloud_ram_user" "ram_users" {
  for_each = {for user in var.ram_users : user.name => user}
  name     = each.value.name
  comments = each.value.description
  force    = true
}

resource "alicloud_ram_login_profile" "profile" {
  for_each = {for user in var.ram_users : user.name => user if user.enable_console_login}
  user_name = each.value.name
  password  = var.ram_user_initial_pwd
  password_reset_required = true
  depends_on = [alicloud_ram_user.ram_users]
}

resource "alicloud_ram_access_key" "ak" {
  for_each = {for user in var.ram_users : user.name => user if user.enable_api_access}
  user_name   = each.value.name
  secret_file = "../../${var.account_id}-${each.value.name}.accesskey"
  depends_on = [alicloud_ram_user.ram_users]
}

resource "alicloud_ram_role" "ram_roles" {
  for_each = {for role in var.ram_roles : role.name => role}
  name = each.value.name
  description = each.value.description
  document    = <<EOF
  {
    "Statement": [
        {
            "Action": "sts:AssumeRole",
            "Condition": {
                "StringEquals": {
                    "saml:recipient": "https://signin.aliyun.com/saml-role/sso"
                }
            },
            "Effect": "Allow",
            "Principal": {
                "Federated": [
                    "acs:ram::${var.account_id}:saml-provider/${var.sso_provider_name}"
                ]
            }
        }
    ],
    "Version": "1"
  }
  EOF
  force = true
}

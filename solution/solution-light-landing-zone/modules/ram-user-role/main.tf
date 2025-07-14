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

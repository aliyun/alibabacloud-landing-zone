provider "alicloud" {}

data "alicloud_caller_identity" "current" {
}

output "current_user_account_id" {
  value = data.alicloud_caller_identity.current.account_id
}
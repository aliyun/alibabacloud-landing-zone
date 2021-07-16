provider "alicloud" {}

data "alicloud_caller_identity" "current" {
}

output "current_role_arn" {
  value = data.alicloud_caller_identity.current.arn
}
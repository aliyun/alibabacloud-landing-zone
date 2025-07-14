locals {
  account_json                     = fileexists("../var/account.json") ? jsondecode(file("../var/account.json")) : {}
  target_id                        = local.account_json["application_folder_id"]
  policy_name               = var.tag_policy.policy_name
  policy_desc               = var.tag_policy.policy_desc
  user_type                 = var.tag_policy.user_type
  policy_content           = var.policy_content
  target_type              = var.target_type
}

provider "alicloud" {
}


resource "alicloud_tag_policy" "tag_policy" {
  policy_name = local.policy_name
  policy_desc = local.policy_desc
  user_type = local.user_type
  policy_content = local.policy_content
}


resource "alicloud_tag_policy_attachment" "tag_policy_attach" {
  policy_id   = alicloud_tag_policy.tag_policy.id
  target_id   = local.target_id
  target_type = local.target_type
}
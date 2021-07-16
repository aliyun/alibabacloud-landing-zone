provider "alicloud" {
}

resource "alicloud_image" "golden_image" {
  instance_id        = var.ecs_instance_id
  image_name         = format("golden_image_%s", formatdate("YYYY-MM-DD", timestamp()))
  description        = "golden image"
  architecture       = var.golden_image_architecture
  platform           = var.golden_image_platform
  tags = {
    # FinanceDept = "FinanceDeptZiying"
  }
}

data "alicloud_resource_manager_accounts" "created" {
  status = "CreateSuccess"
}

data "alicloud_resource_manager_accounts" "invited" {
  status = "InviteSuccess"
}

data "alicloud_resource_manager_accounts" "promoted" {
  status = "PromoteSuccess"
}

resource "alicloud_image_share_permission" "share_golden_image" {
  for_each = toset(concat(data.alicloud_resource_manager_accounts.created.ids, data.alicloud_resource_manager_accounts.invited.ids, data.alicloud_resource_manager_accounts.promoted.ids))
  image_id           = alicloud_image.golden_image.id
  account_id         = each.key
}

#* Add control policy
resource "alicloud_resource_manager_control_policy" "golden_image" {
  control_policy_name = "prohibit_other_images_except_golden_image"
  description         = "prohibit_other_images_except_golden_image"
  effect_scope        = "RAM"
  policy_document     = <<EOF
{
  "Statement": [
    {
      "Action": ["ecs:RunInstances","ecs:CreateInstance"],
      "Effect": "Deny",
      "Resource": "acs:ecs:*:*:instance/*",
      "Condition": {
        "StringEquals": {
          "ecs:ImageSource": "System"
        }
      }
    },
    {
      "Action": ["ecs:RunInstances","ecs:CreateInstance"],
      "Effect": "Deny",
      "Resource": "acs:ecs:*:*:image/*",
      "Condition": {
        "StringNotLike": {
          "Resource": "acs:ecs:*:*:image/${alicloud_image.golden_image.id}"
        }
      }
    }
  ],
  "Version": "1"
}
  EOF
}

resource "alicloud_resource_manager_control_policy_attachment" "golden_image" {
   policy_id  = alicloud_resource_manager_control_policy.golden_image.id
   target_id  = var.resource_manager_folder_id
}
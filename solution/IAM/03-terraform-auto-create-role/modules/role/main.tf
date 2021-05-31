provider "alicloud" {}

resource "alicloud_ram_role" "ram_role" {
  name = var.role_name
  description = var.role_description
  document    = <<EOF
  {
    "Statement": [
      {
        "Action": "sts:AssumeRole",
        "Effect": "Allow",
        "Principal": {
          "RAM": [
            "acs:ram::${var.account_uid}:root"
          ]
        }
      }
    ],
    "Version": "1"
  }
  EOF
  force = true
}

resource "alicloud_ram_role_policy_attachment" "ram_role_attach_policies" {
  for_each    = toset(var.policies)
  policy_name = each.key
  policy_type = "System"
  role_name   = alicloud_ram_role.ram_role.name
}
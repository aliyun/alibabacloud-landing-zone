resource "alicloud_ram_role" "role" {
  name     = var.role_name
  document = <<EOF
  {
    "Statement": [
      {
        "Action": "sts:AssumeRole",
        "Effect": "Allow",
        "Principal": {
          "RAM": [
            "acs:ram::${var.assume_role_principal_account == "" ? var.ALIYUN__AccountId : var.assume_role_principal_account}:${var.assume_role_principal_type == "RamUser" ? "user" : "role"}/${var.assume_role_principal_name}"
          ]
        }
      }
    ],
    "Version": "1"
  }
  EOF
}

resource "alicloud_ram_policy" "policy" {
  policy_name     = var.policy_name
  policy_document = jsonencode(var.policy_document)
}

resource "alicloud_ram_role_policy_attachment" "attach" {
  policy_name = alicloud_ram_policy.policy.name
  policy_type = alicloud_ram_policy.policy.type
  role_name   = alicloud_ram_role.role.name

  depends_on = [alicloud_ram_policy.policy, alicloud_ram_role.role]
}

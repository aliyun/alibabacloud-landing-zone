resource "alicloud_ram_role" "ram_role" {
  name = var.role_name
  description = var.role_description
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
            "acs:ram::${var.account_uid}:saml-provider/${var.idp_name}"
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
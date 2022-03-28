terraform {
  required_providers {
    alicloud = {
      source = "aliyun/alicloud"
    }
  }
  required_version = ">=0.12"
}

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
                    "acs:ram::${var.account_uid}:saml-provider/${var.sso_provider_name}"
                ]
            }
        }
    ],
    "Version": "1"
  }
  EOF
  force = true
}









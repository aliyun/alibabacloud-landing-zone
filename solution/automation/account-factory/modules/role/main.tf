terraform {
  required_providers {
    alicloud = {
      source = "aliyun/alicloud"
    }
  }
  required_version = ">=0.12"
}


# 这个资源如果要删除的话因为它有关联信息，除非将关联信息一并删除掉，或者在plan的时候加上force=true
# 创建RAM角色并且可信实体类型为：身份提供商，需要指定身份提供商的名称
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









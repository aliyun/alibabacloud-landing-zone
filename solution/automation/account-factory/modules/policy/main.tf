provider "alicloud" {}

# 自定义权限策略,注意：客户可能会定义多个则做好命名的一致.
resource "alicloud_ram_policy" "policy" {
  policy_name = var.policy_name


  # TODO 这个权限策略无法定义到配置文件中。需要支持JSON格式
  policy_document = <<EOF
  {
    "Statement": [
      {
        "Action": [
          "oss:ListObjects",
          "oss:GetObject"
        ],
        "Effect": "Allow",
        "Resource": [
          "acs:oss:*:*:mybucket",
          "acs:oss:*:*:mybucket/*"
        ]
      }
    ],
      "Version": "1"
  }
  EOF


  description = "用户自定义权限策略"
  force       = true
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

# 自定义授权
resource "alicloud_ram_role_policy_attachment" "ram_role_attach_custom_policies" {
  policy_name = alicloud_ram_policy.policy.policy_name
  policy_type = "Custom"
  role_name   = alicloud_ram_role.ram_role.name
}
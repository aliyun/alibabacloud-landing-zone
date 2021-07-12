# 主账号AK，需要填写AK和AK secret
access_key = ""

secret_key = ""

region = "cn-hangzhou"

# 角色列表
ram_roles = {
  "ssoTestRole": {
    description = "Test for Terraform"
    policies = [
      "AliyunLogFullAccess"
    ]
  },
  "ssoTestRole2": {
    description = "Test for Terraform"
    policies = [
      "AliyunLogReadOnlyAccess"
    ]
  }
}

# idp名称
saml_provider_name = "tf-testIdp"

# idp元数据xml文件路径
metadata = "./meta.xml"
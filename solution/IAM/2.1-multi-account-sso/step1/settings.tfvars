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
  }
}

# idp名称
saml_provider_name = "tf-testIdp"

# idp元数据xml文件路径
metadata = "./meta.xml"

# 子账号黑名单，填写子账号uid，在黑名单内的子账号不会创建idp和ram角色
exclude = ["113************"]
# 期望作用在所有子账号上则配置exclude=[]即可。
# exclude = []
# AK，需要手动填写AK和AK Secret
access_key = ""
secret_key = ""
region = "cn-hangzhou"

# 管控策略详细信息
# 策略名称
control_policy_name = "policy1"
# 策略描述
description = ""
# 策略作用范围
effect_scope = "RAM"
# 策略配置
policy_document = {
  "Version":"1",
  "Statement": [
    {
      "Effect": "Deny",
      "Action": [
        "ram:UpdateRole",
        "ram:DeleteRole",
        "ram:AttachPolicyToRole",
        "ram:DetachPolicyFromRole"
      ],
      "Resource":"acs:ram:*:*:role/ResourceDirectoryAccountAccessRole"
    }
  ]
}

# 绑定的资源夹ID组
resource_manager_folder_ids = ["fd-Zn1wkx2Dws","fd-5wKgZmg0ng"]

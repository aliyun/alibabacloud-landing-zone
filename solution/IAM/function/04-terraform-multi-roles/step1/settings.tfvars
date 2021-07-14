# AK，需要手动填写AK和AK Secret
access_key = ""
secret_key = ""
region = "cn-hangzhou"

# RAM用户要去扮演的角色，需要填写角色名称和角色所在账号的uid
ram_roles = [
  {
    role_name = "cloudadmin"
    account_id = "1925726250249818"
  },
  {
    role_name = "networkadmin"
    account_id = "1320526250247518"
  }
]
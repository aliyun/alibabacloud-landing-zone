# 主账号AK，需要填写AK和AK secret
access_key = ""
secret_key = ""
region = "cn-hangzhou"

# 创建文件夹和资源账号
# 创建"prod"和"core"文件夹，下面分别有账号"Bob","Alice"和"Admin","Manager"
resource_directories = {
  "prod": {
    users = ["Bob", "Alice"]
  },
  "core": {
    users = ["Admin", "Manager"]
  }
}


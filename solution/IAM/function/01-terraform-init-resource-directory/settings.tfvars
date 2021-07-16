# 主账号AK，需要填写AK和AK secret
access_key = ""
secret_key = ""
region = "cn-hangzhou"

# 创建文件夹和资源账号
# 创建"prod"和"core"文件夹
# "prod"下有账号"Prod", "Pre-prod", "Dev", "Test", "core"下有账号"Shared Service", "Security", "Networking", "Audit"
resource_directories = {
  "prod": {
    accounts = ["Prod", "Pre-prod", "Dev", "Test"]
  },
  "core": {
    accounts = ["Shared Service", "Security", "Networking", "Audit"]
  }
}


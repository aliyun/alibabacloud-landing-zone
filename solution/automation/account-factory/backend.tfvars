# Modify according to the actual situation
bucket = "bucket-with-terraform-state1"
prefix = "path/mystate"
region = "cn-hangzhou"
tablestore_endpoint = "https://xxxx.cn-hangzhou.ots.aliyuncs.com"
tablestore_table = "statelock"
endpoint = "oss-cn-hangzhou.aliyuncs.com"
# If the backend storage is not under the management account, you need to set the AK/SK of the corresponding account
#access_key = "xxx"
#secret_key = "xxx"
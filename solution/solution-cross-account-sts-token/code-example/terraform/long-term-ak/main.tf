# 配置provider，跨账号扮演角色
provider "alicloud" {
  # 该AK需要具有RAM AssumeRole权限
  access_key = "<your-access-key-id>"
  secret_key = "<your-access-key-secret>"
  # 地域，以华东1（杭州）为例
  region = "cn-hangzhou"
  assume_role {
    # 请替换为您实际要扮演的RAM角色ARN
    # 格式为 acs:ram::${账号 ID}:role/${角色名称}
    role_arn = "<role-arn>"
    # 设置会话权限策略，进一步限制STS Token 的权限，如果指定该权限策略，则 STS Token 最终的权限策略取 RAM 角色权限策略与该权限策略的交集
    # 非必填。示例值：{"Statement": [{"Action": ["*"],"Effect": "Allow","Resource": ["*"]}],"Version":"1"}
    policy = "{\"Statement\": [{\"Action\": [\"*\"],\"Effect\": \"Allow\",\"Resource\": [\"*\"]}],\"Version\":\"1\"}"
    # 角色会话名称
    session_name = "WellArchitectedSolutionDemo"
    # STS Token 有效期，单位：秒
    session_expiration = 3600
  }
}

# 跨账号进行资源操作
# 以获取当前调用者身份信息为例
data "alicloud_caller_identity" "current" {
}

output "caller_identity" {
  value = data.alicloud_caller_identity.current
}

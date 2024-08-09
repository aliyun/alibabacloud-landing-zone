# OIDC身份提供商的ARN
# 格式为 acs:ram::${账号ID}:oidc-provider/ack-rrsa-${集群ID}
variable "oidc_provider_arn" {
  type = string
}

# Pod绑定的RAM角色ARN
# 格式为 acs:ram::${账号ID}:role/${角色名称}
variable "oidc_role_arn" {
  type = string
}

# 集群生成的OIDC Token的文件路径
variable "oidc_token_file" {
  type = string
}

# 配置provider，跨账号扮演角色
provider "alicloud" {
  region = "cn-hangzhou"
  assume_role_with_oidc {
    oidc_provider_arn = var.oidc_provider_arn
    role_arn          = var.oidc_role_arn
    oidc_token_file   = var.oidc_token_file
    role_session_name = "WellArchitectedSolutionDemo"
  }
  assume_role {
    # 请替换为您实际要扮演的RAM角色ARN
    # 格式为 acs:ram::${账号ID}:role/${角色名称}
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

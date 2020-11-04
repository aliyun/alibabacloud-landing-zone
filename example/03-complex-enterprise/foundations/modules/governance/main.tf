######################## 步骤3.1 [配置预防性管控规则]##################


######################## 步骤3.2 [配置发现性管控规则]##################

# 开通 SLS 服务
data "alicloud_log_service" "open" {
  enable = "On"
}

# 升级 config 企业版
# resource "alicloud_config_configuration_recorder" "cloud-config" {
#   enterprise_edition = true
# }

resource "alicloud_config_rule" "ecs-instances-in-vpc" {
  rule_name                       = "ecs-instances-in-vpc"
  source_identifier               = "ecs-instances-in-vpc"
  source_owner                    = "ALIYUN"
  scope_compliance_resource_types = ["ACS::ECS::Instance"]
  description                     = "您账号下所有ECS实例已关联到VPC；若您配置阈值，则关联的VpcId需存在您列出的阈值中，视为“合规”。"
  input_parameters = {
    vpcIds = ""
  }
  risk_level                         = 1
  source_detail_message_type         = "ConfigurationItemChangeNotification"
  multi_account = true
}

resource "alicloud_config_rule" "sg-risky-ports-check" {
  rule_name                       = "sg-risky-ports-check"
  source_identifier               = "sg-risky-ports-check"
  source_owner                    = "ALIYUN"
  scope_compliance_resource_types = ["ACS::ECS::SecurityGroup"]
  description                     = "检测安全组是否开启风险端口，不开启则视为“合规”。"
  input_parameters = {
    "ports": "22,3389,80"
  }
  risk_level                         = 1
  source_detail_message_type         = "ConfigurationItemChangeNotification"
  multi_account = true
}

resource "alicloud_config_rule" "sg-public-access-check" {
  rule_name                       = "sg-public-access-check"
  source_identifier               = "sg-public-access-check"
  source_owner                    = "ALIYUN"
  scope_compliance_resource_types = ["ACS::ECS::SecurityGroup"]
  description                     = "账号下ECS安全组配置不为“0.0.0.0/0”，视为“合规”。"
  input_parameters = {}
  risk_level                         = 1
  source_detail_message_type         = "ConfigurationItemChangeNotification"
  multi_account = true
}

resource "alicloud_config_rule" "ram-user-mfa-check" {
  rule_name                       = "ram-user-mfa-check"
  source_identifier               = "ram-user-mfa-check"
  source_owner                    = "ALIYUN"
  scope_compliance_resource_types = ["ACS::RAM::User"]
  description                     = "检测RAM用户是否开通MFA二次验证登录，如开通则视为“合规”。"
  input_parameters = {}
  risk_level                         = 1
  source_detail_message_type         = "ConfigurationItemChangeNotification"
  multi_account = true
}

######################## 步骤3.3 [配置操作审计]##################

# 创建 OSS Bucket 用于存放 ActionTrail 的日志
resource "alicloud_oss_bucket" "landingzone-enterprise-audit-logs" {
  bucket = var.bucket_audit_logs
  acl    = "private"
}

# 创建 MNS Topic接收日志投递事件的通知
resource "alicloud_mns_topic" "enterprise-topic" {
  name                 = var.mns.topic_name
  maximum_message_size = var.mns.message_size
  logging_enabled      = var.mns.logging_enable
}

# 创建 ActionTrail 日志跟踪
# resource "alicloud_actiontrail_trail" "enterprise-audit-logs" {
#   trail_name      = var.trail_audit_logs
#   event_rw        = "All"
#   oss_bucket_name = alicloud_oss_bucket.landingzone-enterprise-audit-logs.bucket
#   mns_topic_arn = "acs:mns:cn-hangzhou:${var.mns.topic_name}:/topics/${var.master_id}"
#   role_name       = "aliyunserviceroleforactiontrail"
# }
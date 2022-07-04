provider "alicloud" {
  alias      = "ma"
  region     = var.region
}

# assumerole到日志账号
provider "alicloud" {
  alias      = "logarchiveprovider"
  access_key = var.access_key
  secret_key = var.secret_key
  region     = var.region
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", var.log_account_id)
    session_name       = format("%sLandingZoneSetup", var.log_account_id)
    session_expiration = 999
  }
}



# 配置日志审计
resource "alicloud_log_audit" "logaudit" {
  provider          = alicloud.logarchiveprovider
  display_name      = "tf-audit"
  aliuid            = var.log_account_id
  variable_map = {
    "bastion_enabled" = "true",
    "bastion_ttl" = "180",
    "actiontrail_enabled" = "true",
    "actiontrail_ttl" = "180"
  }
  multi_account = var.member_account_list
  resource_directory_type="custom"
}

# 配置SLS的Project
resource "alicloud_log_project" "os_operation_log" {
  provider          = alicloud.logarchiveprovider
  name        = var.log_project_name
  description = "archive all account os operation log"
}

# 配置SLS的Logstore
resource "alicloud_log_store" "os_operation_logstore" {
  provider          = alicloud.logarchiveprovider
  project               = alicloud_log_project.os_operation_log.name
  name                  = var.log_project_logstore_name
  shard_count           = 3
  auto_split            = true
  max_split_shard_count = 60
  append_meta           = true
}


# 索引
resource "alicloud_log_store_index" "os_operation_index" {
  provider          = alicloud.logarchiveprovider
  project  = alicloud_log_project.os_operation_log.name
  logstore = alicloud_log_store.os_operation_logstore.name
  full_text {
    case_sensitive = true
    token          = "#$%^*\r\n"
  }
  field_search {
    name             = "terraform"
    enable_analytics = true
  }
}



###################
#log machine group#
###################
resource "alicloud_log_machine_group" "os_operation_group" {
  provider          = alicloud.logarchiveprovider
  project       = alicloud_log_project.os_operation_log.name
  name          = "tf-machine-group"
  identify_type = "userdefined"
  topic         = "terraform"
  identify_list = var.userdefined
}

###################
#log tail config  #
###################
resource "alicloud_logtail_config" "example" {
  provider          = alicloud.logarchiveprovider
  project      = alicloud_log_project.os_operation_log.name
  logstore     = alicloud_log_store.os_operation_logstore.name
  input_type   = "file"
  log_sample   = "test"
  name         = "tf-log-config"
  output_type  = "LogService"
  # https://www.alibabacloud.com/help/zh/log-service/latest/logtail-configurations#table-xuw-zvz-tp7 参数
  input_detail = <<EOF
                  {
                      "discardUnmatch": false,
                      "enableRawLog": true,
                      "fileEncoding": "gbk",
                      "filePattern": "${var.file_pattern}",
                      "logPath": "${var.file_path}",
                      "logType": "json_log",
                      "maxDepth": 10,
                      "topicFormat": "default"
                  }
                  EOF
}

resource "alicloud_logtail_attachment" "this" {
  provider          = alicloud.logarchiveprovider
  project             = alicloud_log_project.os_operation_log.name
  logtail_config_name = concat(alicloud_logtail_config.example.*.name, [""])[0]
  machine_group_name  = concat(alicloud_log_machine_group.os_operation_group.*.name, [""])[0]
}


###################
#ecs config  #
###################
resource "alicloud_instance" "default" {
  provider = alicloud.ma
  # 可用区,eg: cn-beijing-i
  security_groups   = [var.securitygroup]
  instance_type              = var.instancetype
  # 镜像名称，自定义镜像
  image_id                   = var.image_id
  vswitch_id                 = var.vswitch
  password                   = var.instance_password

  internet_max_bandwidth_out = var.internet_max_bandwidth_out
  # 计费模式. 注意：公司测试账号无法创建按量资源，只能开包年包月
  instance_charge_type       = var.instance_charge_type
  period_unit = var.period_unit
  period = var.period
  force_delete = true
  user_data = local.user_data
}

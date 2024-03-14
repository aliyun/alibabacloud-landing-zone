resource "alicloud_log_alert" "cis_at_pwd_length_policy" {
  count             = contains(var.enabled_alerts, "cis.at.pwd_length_policy") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_pwd_length_policy"
  alert_displayname = "Alert of Abnormal Setting of RAM Password Length Policy"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "账号$${account_id}的RAM密码长度策略设置异常, 操作账号ID：$${ram_user_id},账号名：$${user_name},账号类型:$${user_type}。异常修改次数:$${cnt}, 异常修改内容:RAM密码策略中密码最小长度设置过短，小于预设阈值14。"
  }
  annotations {
    key   = "title"
    value = "RAM密码长度策略异常设置告警"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "__topic__: actiontrail_audit_event and event.eventName: SetPasswordPolicy | SELECT arbitrary(user_type) as user_type, arbitrary(user_name) as user_name, arbitrary(region_id) as region_id, account_id, ram_user_id, count(1) as cnt FROM ( SELECT cast(json_extract(\"event.requestParameterJson\", '$.MinimumPasswordLength') as bigint) as user_min_pwd_len, \"event.userIdentity.type\" as user_type, \"event.userIdentity.userName\" as user_name, \"event.userIdentity.principalId\" as ram_user_id, \"event.acsRegion\" as region_id, \"event.userIdentity.accountId\" as account_id FROM log ) WHERE user_min_pwd_len > 14 or user_min_pwd_len = 0  group by account_id, ram_user_id limit 10000"
    region         = var.project_region
    role_arn       = ""
    start          = "-30m"
    store          = var.log_store
    store_type     = "log"
    time_span_type = "Relative"
  }
  
  severity_configurations {
    eval_condition = {
      condition      = ""
      countCondition = ""
    }
    severity = 8
  }
  
  no_data_fire     = false
  no_data_severity = 6
  send_resolved    = false
  auto_annotation  = false
  
  group_configuration {
    fields = ["account_id","ram_user_id"]
    type   = "custom"
  }

  policy_configuration {
    action_policy_id = "sls.app.actiontrail.builtin"
    alert_policy_id  = "sls.app.actiontrail.builtin"
    repeat_interval  = "2h"
  }
}
  
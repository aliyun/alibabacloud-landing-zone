resource "alicloud_log_alert" "cis_at_pwd_reuse_prevention_policy" {
  count             = contains(var.enabled_alerts, "cis.at.pwd_reuse_prevention_policy") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_pwd_reuse_prevention_policy"
  alert_displayname = "Alert of Setting of RAM Historical Passwords Check Policy"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "账号$${account_id}的RAM历史密码检查策略设置异常, 操作账号ID：$${ram_user_id},账号名：$${user_name},账号类型:$${user_type}。异常修改次数:$${cnt}, 异常修改内容:历史密码检查策略，\"禁止使用前N次密码\"中N的值设置过小, 小于预设阈值4。"
  }
  annotations {
    key   = "title"
    value = "RAM历史密码检查策略异常设置告警"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "__topic__: actiontrail_audit_event and event.eventName: SetPasswordPolicy | SELECT arbitrary(user_type) as user_type, arbitrary(user_name) as user_name, arbitrary(region_id) as region_id, account_id, ram_user_id, count(1) as cnt FROM ( SELECT cast(json_extract(\"event.requestParameterJson\", '$.PasswordReusePrevention') as bigint) as user_max_reuse_prevention, \"event.userIdentity.type\" as user_type, \"event.userIdentity.userName\" as user_name, \"event.userIdentity.principalId\" as ram_user_id, \"event.acsRegion\" as region_id, \"event.userIdentity.accountId\" as account_id FROM log ) WHERE user_max_reuse_prevention > 4 or user_max_reuse_prevention = 0  group by account_id, ram_user_id limit 10000"
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
  
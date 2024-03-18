resource "alicloud_log_alert" "cis_at_abnormal_pwd_mod_cnt" {
  count             = contains(var.enabled_alerts, "cis.at.abnormal_pwd_mod_cnt") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_abnormal_pwd_mod_cnt"
  alert_displayname = "Alert of Abnormal Password Modification Frequency"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "账号$${account_id}过去半小时内密码修改操作频率异常（$${cnt}次密码修改操作），操作账号ID：$${ram_user_id}，操作账号名：$${user_name}，操作账号类型：$${user_type}"
  }
  annotations {
    key   = "title"
    value = "密码修改操作频率异常告警"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "__topic__: actiontrail_audit_event and (((event.serviceName: Ram or event.serviceName: Ims) and event.eventName: ChangePassword) or (event.serviceName: AasCustomer and  event.eventName: PasswordReset)) | select count(1) as cnt, \"event.userIdentity.accountId\" as account_id, \"event.userIdentity.principalId\" as ram_user_id,  arbitrary(\"event.userIdentity.type\") as user_type, arbitrary(\"event.userIdentity.userName\") as user_name group by account_id, ram_user_id limit 10000"
    region         = var.project_region
    role_arn       = ""
    start          = "-30m"
    store          = var.log_store
    store_type     = "log"
    time_span_type = "Relative"
  }
  
  severity_configurations {
    eval_condition = {
      condition      = "cnt > 1"
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
  
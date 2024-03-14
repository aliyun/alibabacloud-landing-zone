resource "alicloud_log_alert" "cis_at_abnormal_login" {
  count             = contains(var.enabled_alerts, "cis.at.abnormal_login") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_abnormal_login"
  alert_displayname = "Account Continuous Login Failure Alert"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "用户$${user_name}(id:$${user_id})过去30分钟内失败登陆$${cnt}次，超过预设阈值5"
  }
  annotations {
    key   = "title"
    value = "用户$${user_name}(id:$${user_id})30分钟内登录失败次数过多"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "__topic__: actiontrail_audit_event and event.eventName: ConsoleSignin and event.errorMessage: * and not event.errorMessage: success | select \"event.userIdentity.principalId\" as user_id, \"event.userIdentity.userName\" as user_name, count(1) as cnt group by user_id, user_name limit 10000"
    region         = var.project_region
    role_arn       = ""
    start          = "-30m"
    store          = var.log_store
    store_type     = "log"
    time_span_type = "Relative"
  }
  
  severity_configurations {
    eval_condition = {
      condition      = "cnt > 5"
      countCondition = ""
    }
    severity = 8
  }
  
  no_data_fire     = false
  no_data_severity = 6
  send_resolved    = false
  auto_annotation  = false
  
  group_configuration {
    fields = ["user_id","user_name"]
    type   = "custom"
  }

  policy_configuration {
    action_policy_id = "sls.app.actiontrail.builtin"
    alert_policy_id  = "sls.app.actiontrail.builtin"
    repeat_interval  = "2h"
  }
}
  
resource "alicloud_log_alert" "cis_at_root_login" {
  count             = contains(var.enabled_alerts, "cis.at.root_login") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_root_login"
  alert_displayname = "Alert for Continuous Login of Root Account"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "账号$${account_id}过去30分钟使用Root用户登陆$${cnt}次，大于指定阈值5"
  }
  annotations {
    key   = "title"
    value = "账号$${account_id}Root用户过去30分钟登陆过于频繁"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "__topic__: actiontrail_audit_event and event.eventName: ConsoleSignin and event.userIdentity.type: root-account | select \"event.userIdentity.accountId\" as account_id, count(1) as cnt group by account_id limit 10000"
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
    severity = 6
  }
  
  no_data_fire     = false
  no_data_severity = 6
  send_resolved    = false
  auto_annotation  = false
  
  group_configuration {
    fields = ["account_id"]
    type   = "custom"
  }

  policy_configuration {
    action_policy_id = "sls.app.actiontrail.builtin"
    alert_policy_id  = "sls.app.actiontrail.builtin"
    repeat_interval  = "2h"
  }
}
  
resource "alicloud_log_alert" "cis_at_unauth_login" {
  count             = contains(var.enabled_alerts, "cis.at.unauth_login") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_unauth_login"
  alert_displayname = "Unauthorized IP Login Alert"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "不在IP白名单内的源IP$${ip}过去30分钟内登录本账号$${cnt}次，登录用户名$${user_name}(id:$${user_id})。"
  }
  annotations {
    key   = "title"
    value = "未授权的IP登录告警"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "__topic__: actiontrail_audit_event and event.eventName: ConsoleSignin and (not event.errorMessage: * or event.errorMessage: success) | select \"event.userIdentity.principalId\" as user_id, \"event.sourceIpAddress\" as ip, arbitrary(\"event.userIdentity.userName\") as user_name, arbitrary(\"event.userIdentity.type\") as user_type, count(DISTINCT __time__) as cnt group by user_id, ip limit 10000"
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
    fields = ["user_id","ip"]
    type   = "custom"
  }

  policy_configuration {
    action_policy_id = "sls.app.actiontrail.builtin"
    alert_policy_id  = "sls.app.actiontrail.builtin"
    repeat_interval  = "2h"
  }
}
  
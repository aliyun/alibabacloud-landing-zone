resource "alicloud_log_alert" "cis_at_off_duty_login" {
  count             = contains(var.enabled_alerts, "cis.at.off_duty_login") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_off_duty_login"
  alert_displayname = "Alert of Login During Non-working Time"

  schedule {
    type     = "FixedRate"
    interval = "1m"
  }

  annotations {
    key   = "desc"
    value = "阿里云账号$${account_id}下的用户$${user_name}（账号ID:$${user_id}，账号类型:$${user_type}）过去1分钟内登录本账号$${cnt}次，登陆IP: $${ip}。"
  }
  annotations {
    key   = "title"
    value = "非工作时间登陆告警"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "__topic__: actiontrail_audit_event and event.eventName: ConsoleSignin and (not event.errorMessage: * or event.errorMessage: success) | select \"event.userIdentity.principalId\" as user_id, array_agg(DISTINCT \"event.sourceIpAddress\") as ip, arbitrary(\"event.userIdentity.accountId\") as account_id, arbitrary(\"event.userIdentity.userName\") as user_name, arbitrary(\"event.userIdentity.type\") as user_type, count(DISTINCT __time__) as cnt group by user_id limit 10000"
    region         = var.project_region
    role_arn       = ""
    start          = "-1m"
    store          = var.log_store
    store_type     = "log"
    time_span_type = "Truncated"
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
    fields = ["user_id"]
    type   = "custom"
  }

  policy_configuration {
    action_policy_id = "actiontrail.calendar_policy"
    alert_policy_id  = "sls.app.actiontrail.builtin"
    repeat_interval  = "1m"
  }
}
  
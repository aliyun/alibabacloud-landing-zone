resource "alicloud_log_alert" "cis_at_trail_off" {
  count             = contains(var.enabled_alerts, "cis.at.trail_off") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_trail_off"
  alert_displayname = "Alert of Attempt to Turn off Trails"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "账号$${account_id}下的Aciontrial跟踪被尝试关闭，尝试进行关闭操作的账号名：$${user_name}，账号类型：$${user_type}, 账号ID: $${ram_user_id}"
  }
  annotations {
    key   = "title"
    value = "尝试关闭跟踪的操作告警"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "__topic__: actiontrail_audit_event and event.serviceName: Actiontrail and (event.eventName: DeleteTrail or event.eventName: StopLogging) | select \"event.userIdentity.accountId\" as account_id, arbitrary(\"event.userIdentity.principalId\") as ram_user_id,  arbitrary(\"event.userIdentity.type\") as user_type, arbitrary(\"event.userIdentity.userName\") as user_name group by account_id"
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
    fields = ["account_id"]
    type   = "custom"
  }

  policy_configuration {
    action_policy_id = "sls.app.actiontrail.builtin"
    alert_policy_id  = "sls.app.actiontrail.builtin"
    repeat_interval  = "2h"
  }
}
  
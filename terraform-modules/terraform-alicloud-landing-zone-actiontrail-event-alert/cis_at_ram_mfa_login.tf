resource "alicloud_log_alert" "cis_at_ram_mfa_login" {
  count             = contains(var.enabled_alerts, "cis.at.ram_mfa_login") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_ram_mfa_login"
  alert_displayname = "Alert of RAM User Login without MFA"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "阿里云账号$${root_account_id}下的RAM子账号$${ram_account_id}（用户名：$${ram_account_name}）过去30分钟内，控制台无MFA登录$${cnt}次。"
  }
  annotations {
    key   = "title"
    value = "RAM子账号无MFA登录告警"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "__topic__: actiontrail_audit_event and event.eventName: ConsoleSignin and event.userIdentity.type: ram-user and event.additionalEventData.mfaChecked: false and (event.errorCode: null or not event.errorCode : *) | select \"event.userIdentity.accountId\" as root_account_id, \"event.userIdentity.principalId\" as ram_account_id, arbitrary(\"event.userIdentity.userName\") as ram_account_name, count(1) as cnt group by root_account_id, ram_account_id limit 1000"
    region         = var.project_region
    role_arn       = ""
    start          = "-30m"
    store          = var.log_store
    store_type     = "log"
    time_span_type = "Relative"
  }
  
  severity_configurations {
    eval_condition = {
      condition      = "cnt > 0"
      countCondition = ""
    }
    severity = 8
  }
  
  no_data_fire     = false
  no_data_severity = 6
  send_resolved    = false
  auto_annotation  = false
  
  group_configuration {
    fields = ["root_account_id","ram_account_id"]
    type   = "custom"
  }

  policy_configuration {
    action_policy_id = "sls.app.actiontrail.builtin"
    alert_policy_id  = "sls.app.actiontrail.builtin"
    repeat_interval  = "2h"
  }
}
  
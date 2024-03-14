resource "alicloud_log_alert" "cis_at_sas_webshell_detection" {
  count             = contains(var.enabled_alerts, "cis.at.sas_webshell_detection") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_sas_webshell_detection"
  alert_displayname = "SAS Webpage Anti-tampering Protection Status Disabled Alert"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "账号$${account_id}下的服务器（uuid: $${uuid}）的云安全中心网页防篡改防护已被关闭。操作账号ID：$${ram_user_id},账号名：$${user_name},账号类型:$${user_type}。"
  }
  annotations {
    key   = "title"
    value = "云安全中心网页防篡改防护关闭告警"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "__topic__: actiontrail_audit_event and event.serviceName: aegis and  event.eventName: ModifyWebLockStatus | SELECT account_id, ram_user_id, uuid, arbitrary(user_type) as user_type, arbitrary(user_name) as user_name FROM ( SELECT \"event.userIdentity.accountId\" as account_id, cast(json_extract(\"event.requestParameterJson\", '$.Uuid') as varchar) as uuid, cast(json_extract(\"event.requestParameterJson\", '$.Status') as varchar) as status, \"event.userIdentity.principalId\" as ram_user_id, \"event.userIdentity.type\" as user_type, \"event.userIdentity.userName\" as user_name FROM log) WHERE status = 'off' group by account_id, ram_user_id, uuid limit 10000"
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
    fields = ["account_id","ram_user_id","uuid"]
    type   = "custom"
  }

  policy_configuration {
    action_policy_id = "sls.app.actiontrail.builtin"
    alert_policy_id  = "sls.app.actiontrail.builtin"
    repeat_interval  = "2h"
  }
}
  
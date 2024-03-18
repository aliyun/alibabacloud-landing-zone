resource "alicloud_log_alert" "cis_at_unauth_apicall" {
  count             = contains(var.enabled_alerts, "cis.at.unauth_apicall") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_unauth_apicall"
  alert_displayname = "Alert for Unauthorized API calls"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "源IP：$${source_ip}对账号$${account_id}下的$${service_name}服务过去30分钟内未授权API调用次数过多($${cnt}次）。"
  }
  annotations {
    key   = "title"
    value = "过去30分钟内未授权API调用次数过多。"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "event.eventType: ApiCall and (event.errorCode: NoPermission or event.errorCode: NoPermission.* or event.errorCode: Forbidden or event.errorCode: Forbbiden or event.errorCode: Forbidden.* or event.errorCode: InvalidAccessKeyId or event.errorCode: InvalidAccessKeyId.* or event.errorCode: InvalidSecurityToken or event.errorCode: InvalidSecurityToken.* or event.errorCode: SignatureDoesNotMatch or event.errorCode: InvalidAuthorization or event.errorCode: AccessForbidden or event.errorCode: NotAuthorized) | select \"event.userIdentity.accountId\" as account_id, \"event.serviceName\" as service_name, \"event.sourceIpAddress\" as source_ip,count(1) as cnt group by account_id, service_name,source_ip order by cnt desc limit 10000"
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
    fields = ["account_id","service_name","source_ip"]
    type   = "custom"
  }

  policy_configuration {
    action_policy_id = "sls.app.actiontrail.builtin"
    alert_policy_id  = "sls.app.actiontrail.builtin"
    repeat_interval  = "2h"
  }
}
  
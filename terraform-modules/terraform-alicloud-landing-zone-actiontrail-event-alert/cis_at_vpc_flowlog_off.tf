resource "alicloud_log_alert" "cis_at_vpc_flowlog_off" {
  count             = contains(var.enabled_alerts, "cis.at.vpc_flowlog_off") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_vpc_flowlog_off"
  alert_displayname = "Alert of Abnormal Change of VPC Flowlog Configuration"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "账号$${account_id}下的流日志$${flow_log_id}已被取消或删除。操作账号ID：$${ram_user_id},账号名：$${user_name},账号类型:$${user_type}。"
  }
  annotations {
    key   = "title"
    value = "VPC流日志配置异常变更告警"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "__topic__: actiontrail_audit_event and event.serviceName: Vpc and (event.eventName: DeactiveFlowLog or event.eventName: DeleteFlowLog) | SELECT \"event.userIdentity.accountId\" as account_id, cast(json_extract(\"event.requestParameterJson\", '$.FlowLogId') as varchar) as flow_log_id, \"event.userIdentity.principalId\" as ram_user_id, arbitrary(\"event.userIdentity.type\") as user_type, arbitrary(\"event.userIdentity.userName\") as user_name group by account_id, ram_user_id, flow_log_id limit 10000"
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
    fields = ["account_id","ram_user_id","flow_log_id"]
    type   = "custom"
  }

  policy_configuration {
    action_policy_id = "sls.app.actiontrail.builtin"
    alert_policy_id  = "sls.app.actiontrail.builtin"
    repeat_interval  = "2h"
  }
}
  
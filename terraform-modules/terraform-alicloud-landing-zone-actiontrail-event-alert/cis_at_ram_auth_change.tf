resource "alicloud_log_alert" "cis_at_ram_auth_change" {
  count             = contains(var.enabled_alerts, "cis.at.ram_auth_change") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_ram_auth_change"
  alert_displayname = "Alert of RAM Auth Change"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "阿里云账号ID:$${account_id}, RAM用户类型:$${principal_type}, RAM账号/角色名:$${principal_name}, RAM权限策略名:$${policy_name}, 变更操作:$${event_name}, 变更次数:$${cnt}。"
  }
  annotations {
    key   = "title"
    value = "RAM权限变更告警"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "__topic__: actiontrail_audit_event and ((event.serviceName: ResourceManager and (event.eventName: AttachPolicy or event.eventName: DetachPolicy )) or event.serviceName: Ram and (event.eventName: AttachPolicyToUser or event.eventName: AttachPolicyToGroup or event.eventName: AttachPolicyToRole or event.eventName: DetachPolicyFromUser or event.eventName: DetachPolicyFromGroup or event.eventName: DetachPolicyFromRole)) | SELECT array_agg(distinct event_name) as event_name, count(1) as cnt, json_extract(requestParameterJson, '$.PolicyName') as policy_name, principal_type, account_id, json_extract(requestParameterJson, concat('$.', principal_name_field)) as principal_name from (SELECT \"event.requestParameterJson\" as requestParameterJson, \"event.userIdentity.accountId\" as account_id, \"event.eventName\" as event_name, CASE WHEN \"event.eventName\" like '%PolicyToRole' THEN 'RoleName' WHEN \"event.eventName\" like '%PolicyFromGroup' THEN 'GroupName' WHEN \"event.eventName\" like '%PolicyToUser' THEN 'UserName' ELSE 'PrincipalName' END AS principal_name_field, CASE WHEN \"event.eventName\" like '%PolicyToRole' THEN 'ServiceRole' WHEN \"event.eventName\" like '%PolicyFromGroup' THEN 'IMSGroup' WHEN \"event.eventName\" like '%PolicyToUser' THEN 'IMSUser' ELSE cast(json_extract(\"event.requestParameterJson\", '$.PrincipalType') as varchar) END AS principal_type FROM log) group by policy_name, principal_name, account_id,principal_type limit 1000"
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
    fields = ["account_id","principal_name","principal_type","policy_name"]
    type   = "custom"
  }

  policy_configuration {
    action_policy_id = "sls.app.actiontrail.builtin"
    alert_policy_id  = "sls.app.actiontrail.builtin"
    repeat_interval  = "2h"
  }
}
  
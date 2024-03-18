resource "alicloud_log_alert" "cis_at_rds_access_whitelist" {
  count             = contains(var.enabled_alerts, "cis.at.rds_access_whitelist") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_rds_access_whitelist"
  alert_displayname = "Alert of Abnormal Setting for RDS Instance Access Whitelist"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "账号$${account_id}下的RDS实例$${db_instance_id}的访问白名单被开放为$${security_ips}，请检查是否存在风险。操作账号ID：$${ram_user_id},账号名：$${user_name},账号类型:$${user_type}。"
  }
  annotations {
    key   = "title"
    value = "RDS实例访问白名单异常设置告警"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "__topic__: actiontrail_audit_event and event.serviceName: Rds and event.eventName: ModifySecurityIps | SELECT account_id, ram_user_id, arbitrary(user_type) as user_type, arbitrary(user_name) as user_name, resourceArray[db_num] as db_instance_id, arbitrary(security_ips) as security_ips FROM ( SELECT \"event.userIdentity.accountId\" as account_id, \"event.userIdentity.principalId\" as ram_user_id, \"event.userIdentity.type\" as user_type, \"event.userIdentity.userName\" as user_name, split(\"event.resourceName\", ';') as resourceArray, cast(json_extract(\"event.requestParameterJson\", '$.SecurityIps') as varchar) as security_ips, array_position(split(\"event.resourceType\", ';'), 'ACS::RDS::DBInstance') as db_num FROM log ) WHERE security_ips like '%0.0.0.0%' and db_num > 0 group by account_id, ram_user_id, db_instance_id limit 10000"
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
    fields = ["account_id","ram_user_id","db_instance_id"]
    type   = "custom"
  }

  policy_configuration {
    action_policy_id = "sls.app.actiontrail.builtin"
    alert_policy_id  = "sls.app.actiontrail.builtin"
    repeat_interval  = "2h"
  }
}
  
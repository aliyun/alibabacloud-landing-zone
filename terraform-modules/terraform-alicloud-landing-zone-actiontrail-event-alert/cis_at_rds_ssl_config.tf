resource "alicloud_log_alert" "cis_at_rds_ssl_config" {
  count             = contains(var.enabled_alerts, "cis.at.rds_ssl_config") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_rds_ssl_config"
  alert_displayname = "Alert of Turning off RDS Instance SSL"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "账号$${account_id}下的RDS实例$${db_instance_id}的SSL被关闭，请检查是否存在风险。操作账号ID：$${ram_user_id},账号名：$${user_name},账号类型:$${user_type}。"
  }
  annotations {
    key   = "title"
    value = "RDS实例SSL关闭告警"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "__topic__: actiontrail_audit_event and event.serviceName: Rds and event.eventName: ModifyDBInstanceSSL | SELECT account_id, resourceArray[db_num] as db_instance_id, ram_user_id, arbitrary(user_type) as user_type, arbitrary(user_name) as user_name FROM ( SELECT \"event.userIdentity.accountId\" as account_id, split(\"event.resourceName\", ';') as resourceArray, array_position(split(\"event.resourceType\", ';'), 'ACS::RDS::DBInstance') as db_num, cast(json_extract(\"event.requestParameterJson\", '$.SSLEnabled') as varchar) as sslEnabled, \"event.userIdentity.principalId\" as ram_user_id, \"event.userIdentity.type\" as user_type, \"event.userIdentity.userName\" as user_name FROM log) WHERE db_num > 0 and sslEnabled = '0' group by account_id, ram_user_id, db_instance_id limit 10000"
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
  
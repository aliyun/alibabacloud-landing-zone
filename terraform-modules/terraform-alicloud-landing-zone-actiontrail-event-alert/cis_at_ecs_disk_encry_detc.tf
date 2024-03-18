resource "alicloud_log_alert" "cis_at_ecs_disk_encry_detc" {
  count             = contains(var.enabled_alerts, "cis.at.ecs_disk_encry_detc") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_ecs_disk_encry_detc"
  alert_displayname = "Alert of ECS Cloud Disk Encryption Not Enabled"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "账号$${account_id}下的云盘$${disk_id}在创建时，未开启加密。操作账号ID：$${ram_user_id},账号名：$${user_name},账号类型:$${user_type}。"
  }
  annotations {
    key   = "title"
    value = "ECS云盘加密未开启告警"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "__topic__: actiontrail_audit_event and event.serviceName: Ecs and (event.eventName: CreateDisks or event.eventName: CreateDisk)| SELECT account_id, ram_user_id, resourceArray[num] as disk_id, arbitrary(user_type) as user_type, arbitrary(user_name) as user_name FROM ( SELECT \"event.userIdentity.accountId\" as account_id, \"event.userIdentity.principalId\" as ram_user_id, split(\"event.resourceName\", ';') as resourceArray, array_position(split(\"event.resourceType\", ';'), 'ACS::ECS::Disk') as num, cast(json_extract(\"event.requestParameterJson\", '$.Encrypted') as boolean) as encrypted, \"event.userIdentity.type\" as user_type, \"event.userIdentity.userName\" as user_name FROM log) WHERE num > 0 and encrypted = false group by account_id, ram_user_id, disk_id limit 10000"
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
    fields = ["account_id","ram_user_id","disk_id"]
    type   = "custom"
  }

  policy_configuration {
    action_policy_id = "sls.app.actiontrail.builtin"
    alert_policy_id  = "sls.app.actiontrail.builtin"
    repeat_interval  = "2h"
  }
}
  
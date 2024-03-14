resource "alicloud_log_alert" "cis_at_ecs_force_reboot" {
  count             = contains(var.enabled_alerts, "cis.at.ecs_force_reboot") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_ecs_force_reboot"
  alert_displayname = "Alert of ECS Instance Forced Reboot"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "账号$${account_id}下的ECS实例$${instance_id}（区域：$${region_id}）被强制重启，请检查是否存在风险。操作账号ID：$${ram_user_id},账号名：$${user_name},账号类型:$${user_type}。"
  }
  annotations {
    key   = "title"
    value = "ECS实例强制重启告警"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "__topic__: actiontrail_audit_event and event.serviceName: Ecs and (event.eventName: RebootInstances or event.eventName: RebootInstance) | SELECT account_id, ram_user_id, resourceArray[num] as instance_id, arbitrary(user_type) as user_type, arbitrary(user_name) as user_name, arbitrary(region_id) as region_id FROM ( SELECT \"event.userIdentity.accountId\" as account_id, \"event.userIdentity.principalId\" as ram_user_id, split(\"event.resourceName\", ';') as resourceArray, array_position(split(\"event.resourceType\", ';'), 'ACS::ECS::Instance') as num, cast(json_extract(\"event.requestParameterJson\", '$.ForceReboot') as boolean) as force_reboot, cast(json_extract(\"event.requestParameterJson\", '$.ForceStop') as boolean) as force_stop, \"event.userIdentity.type\" as user_type, \"event.userIdentity.userName\" as user_name, \"event.acsRegion\" as region_id FROM log) WHERE num > 0 and (force_reboot = true or force_stop =true) group by account_id, ram_user_id, instance_id limit 10000"
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
    fields = ["account_id","ram_user_id","instance_id"]
    type   = "custom"
  }

  policy_configuration {
    action_policy_id = "sls.app.actiontrail.builtin"
    alert_policy_id  = "sls.app.actiontrail.builtin"
    repeat_interval  = "2h"
  }
}
  
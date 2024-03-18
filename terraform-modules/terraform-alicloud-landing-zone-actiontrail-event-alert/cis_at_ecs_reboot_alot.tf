resource "alicloud_log_alert" "cis_at_ecs_reboot_alot" {
  count             = contains(var.enabled_alerts, "cis.at.ecs_reboot_alot") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_ecs_reboot_alot"
  alert_displayname = "Excessive Restart of ECS instance"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "账号$${account_id}下的ECS实例$${instance_id}（区域：$${region_id}）过去30分钟内被重启$${cnt}次，请检查是否存在异常。"
  }
  annotations {
    key   = "title"
    value = "ECS实例重启次数过多告警"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "event.serviceName: Ecs and (event.eventName: RebootInstances or event.eventName: RebootInstance) | SELECT  account_id, resourceArray[num] as instance_id, arbitrary(region_id) as region_id, count(*) as cnt FROM (SELECT \"event.userIdentity.accountId\" as account_id, \"event.userIdentity.principalId\" as ram_user_id, split(\"event.resourceName\", ';') as resourceArray, array_position(split(\"event.resourceType\", ';'), 'ACS::ECS::Instance') as num, \"event.acsRegion\" as region_id FROM log) where num > 0 group by account_id, instance_id limit 10000"
    region         = var.project_region
    role_arn       = ""
    start          = "-30m"
    store          = var.log_store
    store_type     = "log"
    time_span_type = "Relative"
  }
  
  severity_configurations {
    eval_condition = {
      condition      = "cnt > 3"
      countCondition = ""
    }
    severity = 8
  }
  
  no_data_fire     = false
  no_data_severity = 6
  send_resolved    = false
  auto_annotation  = false
  
  group_configuration {
    fields = ["account_id","instance_id"]
    type   = "custom"
  }

  policy_configuration {
    action_policy_id = "sls.app.actiontrail.builtin"
    alert_policy_id  = "sls.app.actiontrail.builtin"
    repeat_interval  = "2h"
  }
}
  
resource "alicloud_log_alert" "cis_at_esc_release" {
  count             = contains(var.enabled_alerts, "cis.at.esc_release") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_esc_release"
  alert_displayname = "ECS Instance released Alert"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "账号$${account_id}下的ECS实例$${instance_id}（区域：$${region_id}）已被释放，请检查是否存在风险。操作账号ID：$${ram_user_id},账号名：$${user_name},账号类型:$${user_type}。"
  }
  annotations {
    key   = "title"
    value = "ECS实例释放告警"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "event.serviceName: Ecs and (event.eventName: DeleteInstances or event.eventName: DeleteInstance or event.eventName: Release) and event.eventRW: Write and event.resourceType : \"ACS::ECS::Instance\" | SELECT COALESCE(account_id, recipientAccountId) as account_id, ram_user_id, resourceArray[num] as instance_id, arbitrary(user_type) as user_type, arbitrary(user_name) as user_name,  arbitrary(region_id) as region_id FROM (SELECT \"event.userIdentity.accountId\" as account_id, \"event.recipientAccountId\" as recipientAccountId, \"event.userIdentity.principalId\" as ram_user_id, split(\"event.resourceName\", ';') as resourceArray, array_position(split(\"event.resourceType\", ';'), 'ACS::ECS::Instance') as num, case when \"event.userIdentity.type\"='root-account' then '阿里云主账号' when \"event.userIdentity.type\"='ram-user' then 'RAM用户' when \"event.userIdentity.type\"='assumed-role' then 'RAM角色' when \"event.userIdentity.type\"='system' then '阿里云服务' else \"event.userIdentity.type\" end as user_type, \"event.userIdentity.userName\" as user_name, \"event.acsRegion\" as region_id FROM log) where num > 0 group by account_id, ram_user_id, instance_id limit 10000"
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
  
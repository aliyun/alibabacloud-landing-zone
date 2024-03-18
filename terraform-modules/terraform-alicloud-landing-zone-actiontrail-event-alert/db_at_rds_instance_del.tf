resource "alicloud_log_alert" "db_at_rds_instance_del" {
  count             = contains(var.enabled_alerts, "db.at.rds_instance_del") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "db_at_rds_instance_del"
  alert_displayname = "RDS Instance Released Alert"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "账号$${account_id}下的RDS实例$${instance_id}已被释放。操作账号类型:$${user_type}，操作账号ID:$${ram_user_id}，操作账户用户名:$${user_name}。"
  }
  annotations {
    key   = "title"
    value = "RDS数据库实例释放告警"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "event.serviceName: RDS and (event.eventName: DeleteDBInstance or event.eventName: Release or event.eventName: DestroyDBInstance) | SELECT COALESCE(account_id, recipientAccountId) as account_id, resourceArray[num] as instance_id, ram_user_id,  user_type, user_name FROM (SELECT \"event.userIdentity.accountId\" as account_id, \"event.recipientAccountId\" as recipientAccountId, \"event.userIdentity.principalId\" as ram_user_id, split(\"event.resourceName\", ';') as resourceArray, array_position(split(\"event.resourceType\", ';'), 'ACS::RDS::DBInstance') as num, case when \"event.userIdentity.type\"='root-account' then '阿里云主账号' when \"event.userIdentity.type\"='ram-user' then 'RAM用户' when \"event.userIdentity.type\"='assumed-role' then 'RAM角色' when \"event.userIdentity.type\"='system' then '阿里云服务' else \"event.userIdentity.type\" end as user_type, \"event.userIdentity.userName\" as user_name FROM log ) where num > 0"
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
    fields = ["account_id","instance_id"]
    type   = "custom"
  }

  policy_configuration {
    action_policy_id = "sls.app.actiontrail.builtin"
    alert_policy_id  = "sls.app.actiontrail.builtin"
    repeat_interval  = "2h"
  }
}
  
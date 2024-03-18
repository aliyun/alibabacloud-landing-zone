resource "alicloud_log_alert" "cis_at_ram_policy_change" {
  count             = contains(var.enabled_alerts, "cis.at.ram_policy_change") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_ram_policy_change"
  alert_displayname = "RAM policy Change Alert"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "账号$${account_id}发生RAM策略变更。RAM策略名称：$${policy_name}, 变更类型：$${event_name}。操作账号ID：$${ram_user_id},账号名：$${user_name},账号类型:$${user_type}。"
  }
  annotations {
    key   = "title"
    value = "账号$${account_id}发生RAM策略变更"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "(event.serviceName: ResourceManager or event.serviceName: Ram) and (event.eventName: CreatePolicy or event.eventName: DeletePolicy or event.eventName: CreatePolicyVersion or event.eventName: UpdatePolicyVersion or event.eventName: SetDefaultPolicyVersion or event.eventName: DeletePolicyVersion) | select COALESCE(account_id, recipientAccountId) as account_id, ram_user_id, event_name, resourceArray[num] as policy_name, arbitrary(user_type) as user_type, arbitrary(user_name) as user_name FROM (SELECT \"event.userIdentity.accountId\" as account_id, \"event.recipientAccountId\" as recipientAccountId, split(\"event.resourceName\", ';') as resourceArray, array_position(split(\"event.resourceType\", ';'), 'ACS::RAM::Policy') as num,  \"event.eventName\" as event_name, \"event.userIdentity.principalId\" as ram_user_id, case when \"event.userIdentity.type\"='root-account' then '阿里云主账号' when \"event.userIdentity.type\"='ram-user' then 'RAM用户' when \"event.userIdentity.type\"='assumed-role' then 'RAM角色' when \"event.userIdentity.type\"='system' then '阿里云服务' else \"event.userIdentity.type\" end as user_type, \"event.userIdentity.userName\" as user_name FROM log) where num > 0  group by account_id, ram_user_id, event_name, policy_name limit 5000"
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
    severity = 6
  }
  
  no_data_fire     = false
  no_data_severity = 6
  send_resolved    = false
  auto_annotation  = false
  
  group_configuration {
    fields = ["account_id","ram_user_id","event_name","policy_name"]
    type   = "custom"
  }

  policy_configuration {
    action_policy_id = "sls.app.actiontrail.builtin"
    alert_policy_id  = "sls.app.actiontrail.builtin"
    repeat_interval  = "2h"
  }
}
  
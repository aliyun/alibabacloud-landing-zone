resource "alicloud_log_alert" "cis_at_cfw_assets_protec_off" {
  count             = contains(var.enabled_alerts, "cis.at.cfw_assets_protec_off") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_cfw_assets_protec_off"
  alert_displayname = "Alert of Turning off of Cloudfirewall Protection for Assets"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "账号$${account_id}下的资产（实例IP：$${asset_ip}）的云防火墙防护被关闭。操作账号ID：$${ram_user_id},账号名：$${user_name},账号类型:$${user_type}。"
  }
  annotations {
    key   = "title"
    value = "资产的云防火墙防护关闭告警"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "__topic__: actiontrail_audit_event and event.serviceName: Cloudfw and event.eventName: PutDisableFwSwitch | SELECT account_id, ram_user_id, resourceArray[num] as asset_ip, arbitrary(user_type) as user_type, arbitrary(user_name) as user_name FROM ( select \"event.userIdentity.accountId\" as account_id, \"event.userIdentity.principalId\" as ram_user_id, split(\"event.resourceName\", ';') as resourceArray, array_position(split(\"event.resourceType\", ';'), 'ACS::CloudFirewall::Asset') as num, \"event.userIdentity.type\" as user_type, \"event.userIdentity.userName\" as user_name FROM log) WHERE num > 0 group by account_id, ram_user_id, asset_ip limit 10000"
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
    fields = ["account_id","ram_user_id","asset_ip"]
    type   = "custom"
  }

  policy_configuration {
    action_policy_id = "sls.app.actiontrail.builtin"
    alert_policy_id  = "sls.app.actiontrail.builtin"
    repeat_interval  = "2h"
  }
}
  
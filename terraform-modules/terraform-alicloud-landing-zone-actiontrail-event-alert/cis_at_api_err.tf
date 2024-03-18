resource "alicloud_log_alert" "cis_at_api_err" {
  count             = contains(var.enabled_alerts, "cis.at.api_err") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_api_err"
  alert_displayname = "Alert of Frequency of API Error"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "过去30分钟内，账号$${account_id}下的API调用错误频率过高（$${cnt}次），超过预设阈值（0次）。"
  }
  annotations {
    key   = "title"
    value = "API错误频率告警"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "__topic__: actiontrail_audit_event and event.errorCode: * | select COALESCE(\"event.userIdentity.accountId\", \"event.recipientAccountId\") as account_id, arbitrary(\"event.userIdentity.principalId\") as ram_user_id,  arbitrary(case when \"event.userIdentity.type\"='root-account' then '阿里云主账号' when \"event.userIdentity.type\"='ram-user' then 'RAM用户' when \"event.userIdentity.type\"='assumed-role' then 'RAM角色' when \"event.userIdentity.type\"='system' then '阿里云服务' else \"event.userIdentity.type\" end) as user_type, arbitrary(\"event.userIdentity.userName\") as user_name, count(1) as cnt group by account_id"
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
    fields = ["account_id"]
    type   = "custom"
  }

  policy_configuration {
    action_policy_id = "sls.app.actiontrail.builtin"
    alert_policy_id  = "sls.app.actiontrail.builtin"
    repeat_interval  = "2h"
  }
}
  
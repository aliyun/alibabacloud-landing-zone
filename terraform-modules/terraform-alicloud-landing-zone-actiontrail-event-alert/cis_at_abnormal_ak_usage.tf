resource "alicloud_log_alert" "cis_at_abnormal_ak_usage" {
  count             = contains(var.enabled_alerts, "cis.at.abnormal_ak_usage") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_abnormal_ak_usage"
  alert_displayname = "Alert of Frequency of AK Abnormal Usage"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "过去30分钟内，账号$${account_id}下AccessKeyID $${access_key_id}使用的异常频率过高($${fail_cnt}次)。"
  }
  annotations {
    key   = "title"
    value = "AK使用的异常频率告警"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "__topic__: actiontrail_audit_event and event.errorCode is not NULL and event.errorCode != '' and event.userIdentity.accessKeyId: * | select date_format(min(__time__), '%Y-%m-%d %H:%i:%S') as min_time, date_format(max(__time__), '%Y-%m-%d %H:%i:%S') as latest_time, \"event.userIdentity.accountId\" as account_id, \"event.userIdentity.accessKeyId\" as access_key_id, sum(case when \"event.errorCode\" is NULL or \"event.errorCode\" = '' then 0 else 1 end) as fail_cnt group by account_id, access_key_id limit 10000"
    region         = var.project_region
    role_arn       = ""
    start          = "-30m"
    store          = var.log_store
    store_type     = "log"
    time_span_type = "Relative"
  }
  
  severity_configurations {
    eval_condition = {
      condition      = "fail_cnt > 0"
      countCondition = ""
    }
    severity = 8
  }
  
  no_data_fire     = false
  no_data_severity = 6
  send_resolved    = false
  auto_annotation  = false
  
  group_configuration {
    fields = ["account_id","access_key_id"]
    type   = "custom"
  }

  policy_configuration {
    action_policy_id = "sls.app.actiontrail.builtin"
    alert_policy_id  = "sls.app.actiontrail.builtin"
    repeat_interval  = "2h"
  }
}
  
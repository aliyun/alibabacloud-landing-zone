resource "alicloud_log_alert" "cis_at_root_ak_usage" {
  count             = contains(var.enabled_alerts, "cis.at.root_ak_usage") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_root_ak_usage"
  alert_displayname = "Root Account AK Usage Detection"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "Root用户$${account_id}使用了根密钥$${access_key_id}，成功次数$${success_cnt}，失败次数：$${fail_cnt}，最近时间$${latest_time}, 最早时间$${min_time}"
  }
  annotations {
    key   = "title"
    value = "Root用户$${account_id}使用了Root密钥$${access_key_id}"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "__topic__: actiontrail_audit_event and event.userIdentity.type: root-account and event.userIdentity.accessKeyId: * and not event.userIdentity.accessKeyId: NULL | select date_format(min(__time__), '%Y-%m-%d %H:%i:%S') as min_time, date_format(max(__time__), '%Y-%m-%d %H:%i:%S') as latest_time, \"event.userIdentity.accountId\" as account_id, \"event.userIdentity.accessKeyId\" as access_key_id, sum(case when \"event.errorCode\" is NULL or \"event.errorCode\" = '' then 1 else 0 end) as success_cnt, sum(case when \"event.errorCode\" is NULL or \"event.errorCode\" = '' then 0 else 1 end) as fail_cnt group by account_id, access_key_id limit 10000"
    region         = var.project_region
    role_arn       = ""
    start          = "-30m"
    store          = var.log_store
    store_type     = "log"
    time_span_type = "Relative"
  }
  
  severity_configurations {
    eval_condition = {
      condition      = "success_cnt > 0"
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
  
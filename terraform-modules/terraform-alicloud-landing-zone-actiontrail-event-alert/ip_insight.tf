resource "alicloud_log_alert" "ip_insight" {
  count             = contains(var.enabled_alerts, "ip_insight") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "ip_insight"
  alert_displayname = "IpInsight Alert(Old Version)"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "区域$${regions}发生告警，访问IP为$${ips}，请尽快查看。详细日志请前往操作审计控制台-Insight查看！"
  }
  annotations {
    key   = "title"
    value = "IpInsight告警"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "event.insightDetails.insightType: IpInsight | select array_agg(distinct \"event.acsRegion\") as regions, array_agg(distinct \"event.insightDetails.sourceIpAddress\") as ips, count(1) as cnt from log"
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
    fields = []
    type   = "no_group"
  }

  policy_configuration {
    action_policy_id = "sls.app.actiontrail.builtin"
    alert_policy_id  = "sls.app.actiontrail.builtin"
    repeat_interval  = "2h"
  }
}
  
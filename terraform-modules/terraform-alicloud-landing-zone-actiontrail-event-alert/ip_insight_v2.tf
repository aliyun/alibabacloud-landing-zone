resource "alicloud_log_alert" "ip_insight_v2" {
  count             = contains(var.enabled_alerts, "ip_insight_v2") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "ip_insight_v2"
  alert_displayname = var.lang == "en-US" ? "IpInsight Alert(New Version)" : "IpInsight告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.ip_insight_v2"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
      "default.action_policy" = var.action_policy_id
    }
  }

  depends_on = [
    alicloud_log_resource_record.action_policy
  ]
}
  
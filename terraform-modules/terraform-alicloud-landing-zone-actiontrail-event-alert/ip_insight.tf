resource "alicloud_log_alert" "ip_insight" {
  count             = contains(var.enabled_alerts, "ip_insight") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "ip_insight"
  alert_displayname = var.lang == "en-US" ? "IpInsight Alert(Old Version)" : "IpInsight告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.ip_insight"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
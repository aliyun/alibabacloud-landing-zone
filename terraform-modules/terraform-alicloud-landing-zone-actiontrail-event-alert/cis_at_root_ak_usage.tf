resource "alicloud_log_alert" "cis_at_root_ak_usage" {
  count             = contains(var.enabled_alerts, "cis.at.root_ak_usage") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_root_ak_usage"
  alert_displayname = var.lang == "en-US" ? "Root Account AK Usage Detection" : "Root AK使用检测"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.root_ak_usage"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
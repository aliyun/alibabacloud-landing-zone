resource "alicloud_log_alert" "cis_at_unauth_apicall" {
  count             = contains(var.enabled_alerts, "cis.at.unauth_apicall") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_unauth_apicall"
  alert_displayname = var.lang == "en-US" ? "Alert for Unauthorized API calls" : "未授权的API调用告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.unauth_apicall"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
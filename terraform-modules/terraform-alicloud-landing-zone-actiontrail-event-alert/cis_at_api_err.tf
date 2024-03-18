resource "alicloud_log_alert" "cis_at_api_err" {
  count             = contains(var.enabled_alerts, "cis.at.api_err") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_api_err"
  alert_displayname = var.lang == "en-US" ? "Alert of Frequency of API Error" : "API错误频率告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.api_err"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
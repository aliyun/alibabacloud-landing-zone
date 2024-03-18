resource "alicloud_log_alert" "cis_at_off_duty_login" {
  count             = contains(var.enabled_alerts, "cis.at.off_duty_login") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_off_duty_login"
  alert_displayname = var.lang == "en-US" ? "Alert of Login During Non-working Time" : "非工作时间登陆告警"

  schedule {
    type     = "FixedRate"
    interval = "1m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.off_duty_login"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
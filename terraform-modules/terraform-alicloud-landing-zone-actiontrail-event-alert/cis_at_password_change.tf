resource "alicloud_log_alert" "cis_at_password_change" {
  count             = contains(var.enabled_alerts, "cis.at.password_change") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_password_change"
  alert_displayname = var.lang == "en-US" ? "Alert of Attempt to Modify Password Policy" : "尝试修改密码策略的事件告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.password_change"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
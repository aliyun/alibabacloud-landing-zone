resource "alicloud_log_alert" "cis_at_root_login" {
  count             = contains(var.enabled_alerts, "cis.at.root_login") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_root_login"
  alert_displayname = var.lang == "en-US" ? "Alert for Continuous Login of Root Account" : "Root用户控制台登录次数控制"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.root_login"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
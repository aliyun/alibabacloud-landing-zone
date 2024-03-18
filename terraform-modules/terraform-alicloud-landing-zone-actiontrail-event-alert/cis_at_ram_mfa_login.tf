resource "alicloud_log_alert" "cis_at_ram_mfa_login" {
  count             = contains(var.enabled_alerts, "cis.at.ram_mfa_login") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_ram_mfa_login"
  alert_displayname = var.lang == "en-US" ? "Alert of RAM User Login without MFA" : "RAM子账号无MFA登录告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.ram_mfa_login"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
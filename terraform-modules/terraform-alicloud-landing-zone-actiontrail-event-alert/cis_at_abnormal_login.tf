resource "alicloud_log_alert" "cis_at_abnormal_login" {
  count             = contains(var.enabled_alerts, "cis.at.abnormal_login") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_abnormal_login"
  alert_displayname = var.lang == "en-US" ? "Account Continuous Login Failure Alert" : "账号连续登录失败告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.abnormal_login"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
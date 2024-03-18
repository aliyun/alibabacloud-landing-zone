resource "alicloud_log_alert" "cis_at_pwd_login_attemp_policy" {
  count             = contains(var.enabled_alerts, "cis.at.pwd_login_attemp_policy") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_pwd_login_attemp_policy"
  alert_displayname = var.lang == "en-US" ? "Alert of Abnoraml Settings for RAM Password Login Retry Policy" : "RAM密码登录重试策略异常设置告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.pwd_login_attemp_policy"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
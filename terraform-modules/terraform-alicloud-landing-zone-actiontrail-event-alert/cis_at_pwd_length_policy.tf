resource "alicloud_log_alert" "cis_at_pwd_length_policy" {
  count             = contains(var.enabled_alerts, "cis.at.pwd_length_policy") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_pwd_length_policy"
  alert_displayname = var.lang == "en-US" ? "Alert of Abnormal Setting of RAM Password Length Policy" : "RAM密码长度策略异常设置告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.pwd_length_policy"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
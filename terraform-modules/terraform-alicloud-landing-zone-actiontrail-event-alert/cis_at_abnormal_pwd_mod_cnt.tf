resource "alicloud_log_alert" "cis_at_abnormal_pwd_mod_cnt" {
  count             = contains(var.enabled_alerts, "cis.at.abnormal_pwd_mod_cnt") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_abnormal_pwd_mod_cnt"
  alert_displayname = var.lang == "en-US" ? "Alert of Abnormal Password Modification Frequency" : "密码修改操作频率异常告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.abnormal_pwd_mod_cnt"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
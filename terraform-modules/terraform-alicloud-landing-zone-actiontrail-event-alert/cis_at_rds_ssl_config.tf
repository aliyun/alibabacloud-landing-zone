resource "alicloud_log_alert" "cis_at_rds_ssl_config" {
  count             = contains(var.enabled_alerts, "cis.at.rds_ssl_config") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_rds_ssl_config"
  alert_displayname = var.lang == "en-US" ? "Alert of Turning off RDS Instance SSL" : "RDS实例SSL关闭告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.rds_ssl_config"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
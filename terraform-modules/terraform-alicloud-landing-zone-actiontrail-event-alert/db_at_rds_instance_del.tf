resource "alicloud_log_alert" "db_at_rds_instance_del" {
  count             = contains(var.enabled_alerts, "db.at.rds_instance_del") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "db_at_rds_instance_del"
  alert_displayname = var.lang == "en-US" ? "RDS Instance Released Alert" : "RDS实例释放告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.db.at.rds_instance_del"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
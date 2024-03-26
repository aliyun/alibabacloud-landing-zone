resource "alicloud_log_alert" "cis_at_rds_sql_audit" {
  count             = contains(var.enabled_alerts, "cis.at.rds_sql_audit") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_rds_sql_audit"
  alert_displayname = var.lang == "en-US" ? "Alert of Turning off RDS SQL Insight" : "RDS实例SQL洞察关闭告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.rds_sql_audit"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
      "default.action_policy" = var.action_policy_id
    }
  }

  depends_on = [
    alicloud_log_resource_record.action_policy
  ]
}
  
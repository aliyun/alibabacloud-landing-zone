resource "alicloud_log_alert" "cis_at_cfw_log_off" {
  count             = contains(var.enabled_alerts, "cis.at.cfw_log_off") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_cfw_log_off"
  alert_displayname = var.lang == "en-US" ? "Alert of Turning off of Cloudfirewall Log Analysis" : "云防火墙日志分析功能关闭告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.cfw_log_off"
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
  
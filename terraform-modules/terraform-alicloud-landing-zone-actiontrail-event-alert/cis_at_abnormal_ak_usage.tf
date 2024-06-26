resource "alicloud_log_alert" "cis_at_abnormal_ak_usage" {
  count             = contains(var.enabled_alerts, "cis.at.abnormal_ak_usage") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_abnormal_ak_usage"
  alert_displayname = var.lang == "en-US" ? "Alert of Frequency of AK Abnormal Usage" : "AK使用的异常频率告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.abnormal_ak_usage"
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
  
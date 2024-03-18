resource "alicloud_log_alert" "cis_at_cfw_loose_block" {
  count             = contains(var.enabled_alerts, "cis.at.cfw_loose_block") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_cfw_loose_block"
  alert_displayname = var.lang == "en-US" ? "Alert of Cloudfirewall Switched to Loose Interception Mode" : "云防火墙威胁引擎切换至宽松拦截模式告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.cfw_loose_block"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
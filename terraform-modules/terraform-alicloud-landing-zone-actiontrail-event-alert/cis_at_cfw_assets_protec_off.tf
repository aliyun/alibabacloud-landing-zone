resource "alicloud_log_alert" "cis_at_cfw_assets_protec_off" {
  count             = contains(var.enabled_alerts, "cis.at.cfw_assets_protec_off") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_cfw_assets_protec_off"
  alert_displayname = var.lang == "en-US" ? "Alert of Turning off of Cloudfirewall Protection for Assets" : "资产的云防火墙防护关闭告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.cfw_assets_protec_off"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
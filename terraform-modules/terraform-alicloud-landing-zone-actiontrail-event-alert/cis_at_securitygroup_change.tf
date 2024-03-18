resource "alicloud_log_alert" "cis_at_securitygroup_change" {
  count             = contains(var.enabled_alerts, "cis.at.securitygroup_change") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_securitygroup_change"
  alert_displayname = var.lang == "en-US" ? "Security Group Configurations Change alert" : "安全组配置变更告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.securitygroup_change"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
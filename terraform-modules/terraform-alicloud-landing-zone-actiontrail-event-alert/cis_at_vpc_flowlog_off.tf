resource "alicloud_log_alert" "cis_at_vpc_flowlog_off" {
  count             = contains(var.enabled_alerts, "cis.at.vpc_flowlog_off") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_vpc_flowlog_off"
  alert_displayname = var.lang == "en-US" ? "Alert of Abnormal Change of VPC Flowlog Configuration" : "VPC流日志配置异常变更告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.vpc_flowlog_off"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
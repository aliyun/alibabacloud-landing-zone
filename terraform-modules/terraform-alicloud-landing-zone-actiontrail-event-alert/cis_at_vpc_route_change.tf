resource "alicloud_log_alert" "cis_at_vpc_route_change" {
  count             = contains(var.enabled_alerts, "cis.at.vpc_route_change") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_vpc_route_change"
  alert_displayname = var.lang == "en-US" ? "VPC Network Route Change Alert" : "VPC网络路由变更告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.vpc_route_change"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
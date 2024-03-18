resource "alicloud_log_alert" "cis_at_ecs_release_protec_off" {
  count             = contains(var.enabled_alerts, "cis.at.ecs_release_protec_off") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_ecs_release_protec_off"
  alert_displayname = var.lang == "en-US" ? "Alert of ECS Instance Release Protection Close" : "ECS实例释放保护关闭告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.ecs_release_protec_off"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
resource "alicloud_log_alert" "cis_at_ecs_reboot_alot" {
  count             = contains(var.enabled_alerts, "cis.at.ecs_reboot_alot") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_ecs_reboot_alot"
  alert_displayname = var.lang == "en-US" ? "Excessive Restart of ECS instance" : "ECS实例重启次数过多告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.ecs_reboot_alot"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
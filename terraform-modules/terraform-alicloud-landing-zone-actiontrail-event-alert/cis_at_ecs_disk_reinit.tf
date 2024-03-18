resource "alicloud_log_alert" "cis_at_ecs_disk_reinit" {
  count             = contains(var.enabled_alerts, "cis.at.ecs_disk_reinit") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_ecs_disk_reinit"
  alert_displayname = var.lang == "en-US" ? "ECS Cloud Disk Reinit Alert" : "ECS云盘重新初始化告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.ecs_disk_reinit"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
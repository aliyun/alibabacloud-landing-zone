resource "alicloud_log_alert" "cis_at_ecs_disk_encry_detc" {
  count             = contains(var.enabled_alerts, "cis.at.ecs_disk_encry_detc") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_ecs_disk_encry_detc"
  alert_displayname = var.lang == "en-US" ? "Alert of ECS Cloud Disk Encryption Not Enabled" : "ECS云盘加密未开启告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.ecs_disk_encry_detc"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
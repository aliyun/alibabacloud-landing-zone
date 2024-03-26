resource "alicloud_log_alert" "cis_at_ecs_disk_release" {
  count             = contains(var.enabled_alerts, "cis.at.ecs_disk_release") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_ecs_disk_release"
  alert_displayname = var.lang == "en-US" ? "ECS Cloud Disk Released Alert" : "ECS云盘释放告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.ecs_disk_release"
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
  
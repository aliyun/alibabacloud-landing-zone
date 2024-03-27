resource "alicloud_log_alert" "cis_at_cloudfirewall_conf_change" {
  count             = contains(var.enabled_alerts, "cis.at.cloudfirewall_conf_change") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_cloudfirewall_conf_change"
  alert_displayname = var.lang == "en-US" ? "VPC Firewall Control Policy Change Alert" : "VPC边界防火墙控制策略变更告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.cloudfirewall_conf_change"
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
  
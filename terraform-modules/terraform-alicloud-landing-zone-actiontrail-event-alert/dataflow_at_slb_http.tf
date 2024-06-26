resource "alicloud_log_alert" "dataflow_at_slb_http" {
  count             = contains(var.enabled_alerts, "dataflow.at.slb_http") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "dataflow_at_slb_http"
  alert_displayname = var.lang == "en-US" ? "LoadBalancer(SLB) HTTP Access Protocol Enabled Alert" : "负载均衡HTTP访问协议开启告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.dataflow.at.slb_http"
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
  
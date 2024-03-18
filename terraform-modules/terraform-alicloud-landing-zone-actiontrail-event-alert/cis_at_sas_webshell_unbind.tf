resource "alicloud_log_alert" "cis_at_sas_webshell_unbind" {
  count             = contains(var.enabled_alerts, "cis.at.sas_webshell_unbind") ? 1 : 0
  version           = "2.0"
  type              = "tpl"
  project_name      = var.project_name
  alert_name        = "cis_at_sas_webshell_unbind"
  alert_displayname = var.lang == "en-US" ? "SAS Webpage Anti-tampering Protection Unbinding Alert" : "云安全中心网页防篡改防护解绑告警"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  template_configuration {
    id   = "sls.app.actiontrail.cis.at.sas_webshell_unbind"
    type = "sys"
    lang = var.lang == "en-US" ? "en" : "cn"
    tokens = {
      "default.project"  = var.project_name
      "default.logstore" = var.log_store
    }
  }
}
  
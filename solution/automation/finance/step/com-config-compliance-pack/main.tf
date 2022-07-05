provider "alicloud" {
}

data "alicloud_config_aggregators" "enterprise" {
  name_regex = var.config_aggregator_name
}

module "compliance_pack" {
  source                             = "../../modules/com-pack"
  for_each                           = {for pack in var.config_compliance_packs : pack.config_compliance_pack_name => pack}
  aggregator_id                      = data.alicloud_config_aggregators.enterprise.aggregators.0.id
  config_compliance_rules            = each.value.config_compliance_rules
  config_compliance_pack_name        = each.value.config_compliance_pack_name
  config_compliance_pack_description = each.value.config_compliance_pack_description
  config_compliance_pack_risk_level  = each.value.config_compliance_pack_risk_level
  config_compliance_pack_template_id = each.value.config_compliance_pack_template_id
}


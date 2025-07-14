# Create config rules
resource "alicloud_config_aggregate_config_rule" "config_rule" {
  for_each = {
    for rule in var.config_compliance_rules : rule.rule_name => rule
  }

  source_owner              = "ALIYUN"
  config_rule_trigger_types = "ConfigurationItemChangeNotification"
  aggregator_id             = var.aggregator_id
  risk_level                = var.config_compliance_pack_risk_level

  aggregate_config_rule_name = each.value.rule_name
  source_identifier          = each.value.rule_identifier
  resource_types_scope       = each.value.resource_types_scope
  input_parameters           = {
  for parameter in try(each.value.parameters, []) : parameter.name => parameter.value
  }
  tag_key_scope              = each.value.tag_key_scope
  tag_value_scope            = each.value.tag_value_scope
}

locals {
  # Transform config rule ids to match compliace pack parameter
  config_rule_ids = [
  for rule_resource in alicloud_config_aggregate_config_rule.config_rule : split(":", rule_resource.id)[1]
  ]
}

# Create a compliance pack and add those config rules to compliance pack
resource "alicloud_config_aggregate_compliance_pack" "compliance_pack" {
  aggregator_id                  = var.aggregator_id
  aggregate_compliance_pack_name = var.config_compliance_pack_name
  description                    = var.config_compliance_pack_description
  risk_level                     = var.config_compliance_pack_risk_level
  compliance_pack_template_id    = var.config_compliance_pack_template_id
  dynamic "config_rule_ids" {
    for_each = local.config_rule_ids

    content {
      config_rule_id = config_rule_ids.value
    }
  }
}

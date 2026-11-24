# Retrieve current account information
data "alicloud_account" "current" {
}

#########################################################
# Preventive Controls
#########################################################
data "alicloud_resource_manager_resource_directories" "default" {}

locals {
  resource_directory_root_folder_id = "${data.alicloud_resource_manager_resource_directories.default.directories.0.root_folder_id}"
}

module "control_policies" {
  source = "./modules/control_policies"

  for_each = {
    for rule in var.preventive_guardrails: rule.rule_name => rule
  }

  name = each.value.rule_name
  description = can(each.value.rule_description) ? each.value.rule_description : ""
  policy_document = each.value.policy_document
  target_id = can(each.value.target_id) ? each.value.target_id : local.resource_directory_root_folder_id
}

#########################################################
# Detective Controls
#########################################################

# Retrieve all the accounts in resource directory
data "alicloud_resource_manager_accounts" "accounts" {
}

locals {
  accounts = data.alicloud_resource_manager_accounts.accounts.accounts
}

# Activate config service
resource "alicloud_config_configuration_recorder" "master" {
  lifecycle {
    ignore_changes = [
      resource_types,
      enterprise_edition
    ]
  }
}

# Create config aggregator using the accounts from resource directory
resource "alicloud_config_aggregator" "enterprise" {

  dynamic "aggregator_accounts" {
    for_each = local.accounts

    content {
      account_id = aggregator_accounts.value["account_id"]
      account_name = aggregator_accounts.value["display_name"]
      account_type = "ResourceDirectory"
    }
  }

  aggregator_accounts {
    account_id   = data.alicloud_account.current.id
    account_name = "MasterAccount"
    account_type = "ResourceDirectory"
  }

  aggregator_type = "RD"
  aggregator_name = var.config_aggreator_name
  description     = var.config_aggreator_description

  lifecycle {
    ignore_changes = [
      aggregator_accounts
    ]
  }

  timeouts {
    create = "2m"
  }
}

# Create config rules
resource "alicloud_config_aggregate_config_rule" "detective_guardrails" {
  for_each = {
    for rule in var.detective_guardrails: rule.rule_name => rule
  }

  aggregate_config_rule_name = each.value.rule_name
  aggregator_id = alicloud_config_aggregator.enterprise.id
  source_identifier = each.value.rule_identifier
  source_owner = "ALIYUN"
  risk_level = var.config_compliance_pack_risk_level
  config_rule_trigger_types  = "ConfigurationItemChangeNotification"
  resource_types_scope       = each.value.resource_types_scope
  input_parameters = {
    for parameter in try(each.value.parameters, []): parameter.name => parameter.value
  }
  tag_key_scope = each.value.tag_scope_key
  tag_value_scope = each.value.tag_scope_value
}

locals {
  # Transform config rule ids to match compliace pack parameter
  config_rule_ids = [
    for rule_resource in alicloud_config_aggregate_config_rule.detective_guardrails: split(":", rule_resource.id)[1]
  ]
}

# Create a compliance pack and add those config rules to compliance pack
resource "alicloud_config_aggregate_compliance_pack" "detective_guardrails" {
  aggregate_compliance_pack_name = var.config_compliance_pack_name
  aggregator_id = alicloud_config_aggregator.enterprise.id
  description = var.config_compliance_pack_description
  risk_level = var.config_compliance_pack_risk_level
  dynamic "config_rule_ids" {
    for_each = local.config_rule_ids

    content {
      config_rule_id = config_rule_ids.value
    }
  }
}
provider "alicloud" {
  region = var.light_landingzone_region
}
# Retrieve current account information
data "alicloud_account" "current" {
}

# Retrieve all the accounts in resource directory
data "alicloud_resource_manager_accounts" "accounts" {
}

locals {
  accounts = data.alicloud_resource_manager_accounts.accounts.accounts
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



module "detective_guardrails" {
  source                             = "../../modules/compliance-pack"
  for_each                           = {for pack in var.config_compliance_packs : pack.config_compliance_pack_name => pack}
  aggregator_id                      = alicloud_config_aggregator.enterprise.id
  config_compliance_rules            = each.value.config_compliance_rules
  config_compliance_pack_name        = each.value.config_compliance_pack_name
  config_compliance_pack_description = each.value.config_compliance_pack_description
  config_compliance_pack_risk_level  = each.value.config_compliance_pack_risk_level
  config_compliance_pack_template_id = each.value.config_compliance_pack_template_id



//  source = "../../modules/terraform-alicloud-landing-zone-guardrails"
//
//  for_each            = {for pack in var.config_compliance_packs : pack.config_compliance_pack_name => pack}
//  detective_guardrails               = each.value.config_compliance_rules
//  config_compliance_pack_name        = each.value.config_compliance_pack_name
//  config_compliance_pack_description = each.value.config_compliance_pack_description
//  config_compliance_pack_risk_level  = each.value.config_compliance_pack_risk_level
//  config_compliance_pack_template_id = each.value.config_compliance_pack_template_id
//  aggregator_id                         =
}
provider "alicloud" {
  region = "cn-shanghai"
}

module "detective_guardrails" {
  source = "../../"
  
  detective_guardrails = var.detective_guardrails
  config_aggreator_name = var.config_aggreator_name
  config_aggreator_description = var.config_aggreator_description
  config_compliance_pack_name = var.config_compliance_pack_name
  config_compliance_pack_description = var.config_compliance_pack_description
  config_compliance_pack_risk_level = var.config_compliance_pack_risk_level
}
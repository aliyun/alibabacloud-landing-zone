variable "config_compliance_packs" {
  type = list(object({
    config_compliance_pack_name        = string
    config_compliance_pack_description = string
    config_compliance_pack_template_id = string
    config_compliance_pack_risk_level  = string
    config_compliance_rules            = list(object({
      rule_name            = string
      rule_identifier      = string
      parameters           = list(object({
        name  = string
        value = string
      }))
      resource_types_scope = list(string)
      tag_key_scope        = string
      tag_value_scope      = string
    }))
  }))

  description = "Compliance packs config"
}

variable "config_aggreator_name" {
  type = string
  default = "Enterprise"
  description = "(optional) aggregator name in config. This aggregator contains all the accounts in resource directory"
}

variable "config_aggreator_description" {
  type = string
  default = "Enterprise aggregator for all accounts in resource directory"
  description = "(optional) aggregator description"
}

variable "light_landingzone_region" {
  type = string
}

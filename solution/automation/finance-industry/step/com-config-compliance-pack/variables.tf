variable "config_aggregator_name" {
  type = string
  description = "The Aggregator name of the global account group associated with RD."
  default = "enterprise"
}

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


variable "config_compliance_pack_name" {
  type        = string
  default     = "Guardrails"
  description = "Compliance pack name"
}

variable "config_compliance_pack_description" {
  type        = string
  default     = "Compliacne pack for detective guardrails"
  description = "Compliance pack description"
}

variable "config_compliance_pack_risk_level" {
  type        = number
  default     = 1
  description = "Compliance pack risk level. Valid values: 1: critical 2: warning 3: info"
}

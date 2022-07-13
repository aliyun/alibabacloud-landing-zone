variable "aggregator_id" {
  type = string
  description = "The ID of the aggregator."
}

variable "config_compliance_rules" {
  type = list(object({
    rule_name = string
    rule_identifier = string
    parameters = list(object({
      name = string
      value = string
    }))
    resource_types_scope = list(string)
    tag_key_scope = string
    tag_value_scope = string
  }))
  description = "detective guardrails, each item in list should be config rule name"
}


variable "config_compliance_pack_name" {
  type = string
  default = "Guardrails"
  description = "(optional) compliance pack name"
}

variable "config_compliance_pack_description" {
  type = string
  default = "Compliacne pack for detective guardrails"
  description = "(optional) compliance pack description"
}

variable "config_compliance_pack_risk_level" {
  type = number
  default = 1
  description = "(optional) compliance pack risk level. Valid values: 1: critical 2: warning 3: info"
}

variable "config_compliance_pack_template_id" {
  type = string
  default = null
  description = "The Template ID of compliance package."
}

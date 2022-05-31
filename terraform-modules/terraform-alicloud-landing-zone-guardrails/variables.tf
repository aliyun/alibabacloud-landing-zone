terraform {
  experiments = [module_variable_optional_attrs]
}
variable "detective_guardrails" {
  type = list(object({
    rule_name = string
    rule_identifier = string
    parameters = optional(list(object({
      name = string
      value = string
    })))
    resource_types_scope = list(string)
    tag_scope_key = optional(string)
    tag_scope_value = optional(string)
  }))
  description = "detective guardrails, each item in list should be config rule name"
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

variable "config_compliance_pack_name" {
  type = string
  default = "Guardrails"
  description = "(optional) compliance pack name which will contain all the detective guardrails"
}

variable "config_compliance_pack_description" {
  type = string
  default = "Compliacne pack for detective guardrails"
  description = "(optional) compliance pack description"
}

variable "config_compliance_pack_risk_level" {
  type = number
  default = 1
  description = "(optional) compliance pack risk level。 Valid values: 1: critical 2: warning 3: info"
}
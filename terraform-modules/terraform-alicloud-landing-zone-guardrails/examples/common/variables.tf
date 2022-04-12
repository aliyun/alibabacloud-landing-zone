variable "detective_guardrails" {
  type = list(object({
    RuleName = string
    RuleIdentifier = string
    Parameters = list(object({
      Name = string
      Value = string
    }))
    TagScope = list(object({
      Key = string
      Value = string
    }))
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
  type = string
  default = "1"
  description = "(optional) compliance pack risk level"
}
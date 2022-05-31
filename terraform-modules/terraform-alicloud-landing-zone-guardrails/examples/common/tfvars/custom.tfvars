detective_guardrails = [
  {
    rule_name = "sg-risky-ports-check"
    rule_identifier = "sg-risky-ports-check"
    parameters = [
      {
        name = "ports"
        value = "22,3389"
      }
    ]
    resource_types_scope = ["ACS::ECS::SecurityGroup"]
    tag_scope_key = ""
    tag_scope_value = ""
  }
]

config_aggregator_name = "Enterprise"
config_aggreator_description = "All member account"
config_compliance_pack_name = "CompliacnePackForGuardrails"
config_compliance_pack_description = "Compliance pack for detective guardrails"
config_compliance_pack_risk_level = 2
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
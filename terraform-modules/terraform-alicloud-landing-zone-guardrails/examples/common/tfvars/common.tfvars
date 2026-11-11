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

preventive_guardrails = [
  {
    rule_name = "DenyCreateRamRole"
    rule_description = "Deny creating RAM role"
    policy_document = <<EOF
{
    "Statement": [
        {
            "Action": [
                "ram:CreateRole"
            ],
            "Resource": "*",
            "Effect": "Deny",
            "Condition": {
                "StringNotLike": {
                    "acs:PrincipalARN": "acs:ram:*:*:role/resourcedirectoryaccountaccessrole"
                }
            }
        }
    ],
    "Version": "1"
}
    EOF
  }
]
# terraform-alicloud-landing-zone-guardrails

Terraform module which used to setup guardrails in your landing zone environment.
Currently, this module only contains detective guardrails.

## Usage

```
module "guardrails" {
  source = "terraform-alicloud-modules/landingzone-guardrails/alicloud"

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
      tag_scope_key = ""
      tag_scope_value = ""
    }
  ]
  config_aggreator_name = "Enterprise"
  config_aggreator_description = "All member accounts in resource directory"
  config_compliance_pack_name = "Guardrails"
  config_compliance_pack_description = "Detective guardrails"
  config_compliance_pack_risk_level = 2
}
```

* `detective_guardrails` list of config rules.
  * `parameters`, `tag_scope_key` and `tag_scope_value` is optional, depends on the rule configurations.
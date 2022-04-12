output "config_aggregator" {
  value = alicloud_config_aggregator.enterprise
}

output "config_rules" {
  value = alicloud_config_aggregate_config_rule.detective_guardrails
}

output "config_compliance_pack" {
  value = alicloud_config_aggregate_compliance_pack.detective_guardrails
}
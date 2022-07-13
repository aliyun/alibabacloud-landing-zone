output "aggregate_compliance_pack_id" {
  description = " The resource ID of Aggregate Compliance Pack. The value is formatted <aggregator_id>:<aggregator_compliance_pack_id>."
  value       = alicloud_config_aggregate_compliance_pack.compliance_pack.id
}
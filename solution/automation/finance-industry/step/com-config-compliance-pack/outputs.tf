# outputs.tf https://learn.hashicorp.com/tutorials/terraform/outputs
output "aggregate_compliance_pack_ids" {
  description = "The resource IDs of Aggregate Compliance Pack"
  value = module.compliance_pack
}
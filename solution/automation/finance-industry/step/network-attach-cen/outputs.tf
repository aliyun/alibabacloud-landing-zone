output "cen_instance_id" {
  description = "The ID of CEN."
  value       = alicloud_cen_instance.cen.id
}

output "transit_router_id" {
  description = "The ID of CEN-TR."
  value = alicloud_cen_transit_router.cen_tr.transit_router_id
}

output "shared_service_account_attachment_id" {
  description = "The ID of transit router attachment."
  value       = module.shared_service_account_cen_attach.attachment_id
}

output "dev_account_attachment_id" {
  description = "The ID of transit router attachment."
  value       = module.dev_account_cen_attach.attachment_id
}

output "prod_account_attachment_id" {
  description = "The ID of transit router attachment."
  value       = module.prod_account_cen_attach.attachment_id
}

output "ops_account_attachment_id" {
  description = "The ID of transit router attachment."
  value       = module.ops_account_cen_attach.attachment_id
}

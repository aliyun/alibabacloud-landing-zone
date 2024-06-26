output "logarchive_central_sls_project" {
  value = format("slsaudit-center-%s-%s", var.logarchive_account_id, var.logarchive_central_region)
}

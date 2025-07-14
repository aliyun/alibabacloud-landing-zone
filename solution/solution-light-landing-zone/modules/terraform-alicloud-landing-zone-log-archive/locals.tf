locals {
  actiontrail_log_archive_enabled = anytrue([var.oss_bucket_name_for_actiontrail != "", var.sls_project_name_for_actiontrail != ""])
  cloud_config_log_archive_enabled = anytrue([var.oss_bucket_name_for_cloud_config != "", var.sls_project_name_for_cloud_config != ""])
}
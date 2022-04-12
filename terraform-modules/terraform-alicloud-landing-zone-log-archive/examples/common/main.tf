provider "alicloud" {
  region = "cn-shanghai"
}

module "log_archive" {
  source = "../../"
  
  log_archive_account_uid = var.log_archive_account_uid
  sls_project_name_for_actiontrail = var.sls_project_name_for_actiontrail
  sls_project_region_for_actiontrail = var.sls_project_region_for_actiontrail
  oss_bucket_name_for_actiontrail = var.oss_bucket_name_for_actiontrail
  sls_project_name_for_cloud_config = var.sls_project_name_for_cloud_config
  oss_bucket_name_for_cloud_config = var.oss_bucket_name_for_cloud_config
  actiontrail_trail_name = var.actiontrail_trail_name
}
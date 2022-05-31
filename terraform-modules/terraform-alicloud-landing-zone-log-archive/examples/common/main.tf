provider "alicloud" {
  alias = "master_account"
  region = "cn-shanghai"
}

provider "alicloud" {
  alias = "log_archive_account"
  region = "cn-shanghai"
}
module "log_archive" {
  source = "../../"

  providers = {
    alicloud.master_account = alicloud.master_account
    alicloud.log_archive_account = alicloud.log_archive_account
  }
  
  sls_project_name_for_actiontrail = var.sls_project_name_for_actiontrail
  sls_project_region_for_actiontrail = var.sls_project_region_for_actiontrail
  oss_bucket_name_for_actiontrail = var.oss_bucket_name_for_actiontrail
  sls_project_name_for_cloud_config = var.sls_project_name_for_cloud_config
  oss_bucket_name_for_cloud_config = var.oss_bucket_name_for_cloud_config
  actiontrail_trail_name = var.actiontrail_trail_name
}
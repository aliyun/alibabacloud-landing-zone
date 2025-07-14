locals {
    account_json              = fileexists("../var/account.json") ? jsondecode(file("../var/account.json")) : {}
    log_archive_account            = local.account_json["log_account_id"]

}
provider "alicloud" {
  alias = "master_account"
  region = var.light_landingzone_region
}

provider "alicloud" {
  alias = "log_archive_account"
  region = var.light_landingzone_region

  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.log_archive_account)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }

}
module "log_archive" {
  source    = "../../modules/terraform-alicloud-landing-zone-log-archive"

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
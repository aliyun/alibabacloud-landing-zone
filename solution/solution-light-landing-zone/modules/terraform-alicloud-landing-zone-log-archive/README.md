# terraform-alicloud-landing-zone-log-archive

Terraform module which used to setup log archive in your landing zone environment.
The logs will be archived to log archive account and others (eg: trail) will be created in the master account.

## Usage

This module requires two providers. One is used as master account, the other one is used as log archive account.

```
provider "alicloud" {
  alias = "master_account"
  ...
}

provider "alicloud" {
  alias = "log_archive_account"
  ...
}

module "log_archive" {
  source = "terraform-alicloud-modules/landingzone-log-archive/alicloud"

  providers = {
    master_account = alicloud.master_account
    log_archive_account = alicloud.log_archive_account
  }

  sls_project_name_for_actiontrail = "log_archive_for_xxxx"
  sls_project_region_for_actiontrail = "cn-shanghai"
  oss_bucket_name_for_actiontrail = "log_archive_for_xxxx"
  sls_project_name_for_cloud_config = "log_archive_for_xxxx"
  oss_bucket_name_for_cloud_config = "log_archive_for_xxxx"
  actiontrail_trail_name = "logarchive"
}
```
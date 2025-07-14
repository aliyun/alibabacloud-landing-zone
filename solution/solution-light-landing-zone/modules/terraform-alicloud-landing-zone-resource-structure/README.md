# terraform-alicloud-landing-zone-resource-structure

Terraform module which used to setup basic multi-account structure for your landing zone environment.
This module will create two folders and two accounts in your resource directory.

## Usage
```
module "resource_structure" {
  source = "terraform-alicloud-modules/landing-zone-resource-structure/alicloud"

  core_folder_name = "Core"
  applications_folder_name = "Applications"
  shared_services_account_name = "SharedServices"
  log_archive_account_name = "LogArchive"
  billing_account_uid = ""
}
```

* `billing_account_uid` defaults to be the master account uid if omitted. That means all the bills in the accounts created will be paid by the master account.
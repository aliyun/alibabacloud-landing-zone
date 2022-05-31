terraform {
  required_providers {
    alicloud = {
      source = "hashicorp/alicloud"
      version = ">= 1.134.0"
      configuration_aliases = [ alicloud.master_account, alicloud.log_archive_account ]
    }
  }
  required_version = ">= 0.13"
}

terraform {
  required_providers {
    alicloud = {
      source = "hashicorp/alicloud"
      version = "1.205.0"
      configuration_aliases = [ alicloud.master_account, alicloud.log_archive_account ]
    }

    local = {
      source = "hashicorp/local"
      version = "2.1.0"
    }
  }


  required_version = ">= 0.13"
}


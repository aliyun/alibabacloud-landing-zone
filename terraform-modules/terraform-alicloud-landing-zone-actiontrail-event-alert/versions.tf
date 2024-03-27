terraform {
  required_providers {
    alicloud = {
      source                = "hashicorp/alicloud"
      version               = "> 1.203.0"
      configuration_aliases = [alicloud.log_resource_record]
    }
  }
  required_version = ">= 0.13"
}

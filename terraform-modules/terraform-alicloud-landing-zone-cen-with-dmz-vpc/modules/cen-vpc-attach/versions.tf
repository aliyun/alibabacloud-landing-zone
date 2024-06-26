terraform {
  required_providers {
    alicloud = {
      source = "aliyun/alicloud"
      version = "1.170.0"
      configuration_aliases = [ alicloud.cen_account, alicloud.vpc_account ]
    }
  }
  required_version = ">=0.13"
}
terraform {
  required_providers {
    alicloud = {
      source = "aliyun/alicloud"
      version = "1.170.0"
      configuration_aliases = [ alicloud.cen_account, alicloud.dmz_vpc_account ]
    }
  }
  required_version = ">= 0.14"
}

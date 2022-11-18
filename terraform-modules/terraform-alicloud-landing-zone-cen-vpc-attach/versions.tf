terraform {
  required_providers {
    alicloud = {
      source = "aliyun/alicloud"
      version = "1.192.0"
      configuration_aliases = [ alicloud.shared_service_account, alicloud.vpc_account ]
    }

    local = {
      source = "hashicorp/local"
      version = "2.1.0"
    }
  }
  required_version = ">=0.12"
}
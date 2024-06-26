terraform {
  required_providers {
    alicloud = {
      source = "aliyun/alicloud"
      version = "1.127.0"
    }

    local = {
      source = "hashicorp/local"
      version = "2.1.0"
    }
  }
  required_version = ">=0.12"
}
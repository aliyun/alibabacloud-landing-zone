terraform {
  required_providers {
    alicloud = {
      source = "aliyun/alicloud"
    }
  }
  required_version = ">=0.12"
}

resource "alicloud_vpc" "vpc" {
  vpc_name   = var.vpc_name
  cidr_block = var.vpc_cidr_block
}

resource "alicloud_vswitch" "vsw" {
  vpc_id            = alicloud_vpc.vpc.id
  vswitch_name      = var.vswitch_name
  cidr_block        = var.switch_cidr_block
  zone_id           = var.zone_id
}

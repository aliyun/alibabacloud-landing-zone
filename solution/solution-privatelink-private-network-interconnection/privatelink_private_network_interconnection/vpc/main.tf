variable "vpc_cidr" {}

terraform {
  required_providers {
    alicloud = {
      source  = "hashicorp/alicloud"
      version = "~> 1.1"
    }
  }
}

resource "alicloud_vpc" "vpc" {
  vpc_name = "privatelink-service"
  cidr_block = var.vpc_cidr
}

output "vpc_id" {
  value = alicloud_vpc.vpc.id
}
output "route_table_id" {
  value = alicloud_vpc.vpc.route_table_id
}

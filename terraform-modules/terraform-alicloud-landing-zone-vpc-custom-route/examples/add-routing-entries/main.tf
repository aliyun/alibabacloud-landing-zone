terraform {
  required_providers {
    alicloud = {
      source  = "aliyun/alicloud"
      version = "1.160.0"
    }
  }
}

provider "alicloud" {
  region = var.region
}


module "dmz_egress_biz_vpc_route" {
  source             = "../.."
  vpc_id             = var.vpc_id
  create_route_table = false
  route_entry_config = [
    {
      name                  = "dmz-egress"
      destination_cidrblock = "0.0.0.0/0"
      nexthop_type          = "Attachment"
      nexthop_id            = var.nexthop_id
    }
  ]
}

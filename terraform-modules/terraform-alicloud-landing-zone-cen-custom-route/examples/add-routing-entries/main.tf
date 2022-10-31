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

locals {
  alb_back_to_source_transit_router_route_entry_config = [
  for routing in var.alb_back_to_source_route :
  {
    route_entry_dest_cidr     = routing
    route_entry_next_hop_type = "Attachment"
    route_entry_name          = "dmz-alb-back-to-source"
    route_entry_description   = "dmz-alb-back-to-source"
    route_entry_next_hop_id   = var.cen_attach_id_dmz_vpc
  }
  ]
}

module "dmz_ingress_tr_route" {
  source                            = "../.."
  create_route_table                = false
  transit_router_id                 = var.transit_router_id
  transit_router_route_entry_config = local.alb_back_to_source_transit_router_route_entry_config
}

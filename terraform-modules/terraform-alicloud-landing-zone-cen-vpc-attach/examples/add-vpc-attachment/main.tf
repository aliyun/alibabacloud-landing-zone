terraform {
  required_providers {
    alicloud = {
      source  = "aliyun/alicloud"
      version = "1.192.0"
    }
  }
}

provider "alicloud" {
  alias  = "shared_service_account"
  region = var.region
  #  assume_role {
  #    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", var.shared_service_account_id)
  #    session_name       = "AccountLandingZoneSetup"
  #    session_expiration = 999
  #  }
}

provider "alicloud" {
  alias  = "vpc_account"
  region = var.region
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", var.vpc_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}


module "cen_attach" {
  source    = "../.."
  providers = {
    alicloud.shared_service_account = alicloud.shared_service_account
    alicloud.vpc_account            = alicloud.vpc_account
  }

  shared_service_account_id = var.shared_service_account_id
  vpc_account_id            = var.vpc_account_id
  cen_instance_id           = var.cen_instance_id
  cen_transit_router_id     = var.cen_transit_router_id
  vpc_id                    = var.vpc_id

  primary_vswitch = {
    vswitch_id = var.primary_vswitch.vswitch_id,
    zone_id    = var.primary_vswitch.zone_id
  }

  secondary_vswitch = {
    vswitch_id = var.secondary_vswitch.vswitch_id,
    zone_id    = var.secondary_vswitch.zone_id
  }

  transit_router_attachment_name = var.transit_router_attachment_name
  transit_router_attachment_desc = var.transit_router_attachment_desc

  route_table_association_enabled = var.route_table_association_enabled
  route_table_propagation_enabled = var.route_table_propagation_enabled
}

locals {
  shared_service_account_id = var.shared_service_account_id
  biz_vpc_1_account_id      = var.biz_vpc_1_account_id
  biz_vpc_2_account_id      = var.biz_vpc_2_account_id

  region                = var.region
  dmz_vpc_id            = var.dmz_vpc_id
  cen_attach_id_dmz_vpc = var.cen_attach_id_dmz_vpc
  transit_router_id     = var.transit_router_id

  biz_vpc_1_id            = var.biz_vpc_1_id
  biz_vpc_1_cidr          = var.biz_vpc_1_cidr
  cen_attach_id_biz_vpc_1 = var.cen_attach_id_biz_vpc_1

  biz_vpc_2_id            = var.biz_vpc_2_id
  cen_attach_id_biz_vpc_2 = var.cen_attach_id_biz_vpc_2


  nat_gateway_name             = var.nat_gateway_config.name
  vswitch_id_nat_gateway       = var.nat_gateway_config.vswitch_id
  snat_source_cidr_list        = var.nat_gateway_config.snat_source_cidr_list
  alb_instance_deploy_config   = var.alb_instance_deploy_config
  server_group_backend_servers = var.server_group_backend_servers

  alb_back_to_source_vpc_route_entry_config = [
  for routing in var.alb_back_to_source_route :
  {
    name                  = "dmz-alb-back-to-source"
    destination_cidrblock = routing
    nexthop_type          = "Attachment"
    nexthop_id            = local.cen_attach_id_biz_vpc_1
  }
  ]

  alb_back_to_source_transit_router_route_entry_config = [
  for routing in var.alb_back_to_source_route :
  {
    route_entry_dest_cidr     = routing
    route_entry_next_hop_type = "Attachment"
    route_entry_name          = "dmz-alb-back-to-source"
    route_entry_description   = "dmz-alb-back-to-source"
    route_entry_next_hop_id   = local.cen_attach_id_dmz_vpc
  }
  ]

}

provider "alicloud" {
  alias  = "shared_service_account"
  region = local.region
#  assume_role {
#    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.shared_service_account_id)
#    session_name       = "AccountLandingZoneSetup"
#    session_expiration = 999
#  }
}

provider "alicloud" {
  alias  = "biz_vpc_1_account"
  region = local.region
#  assume_role {
#    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.biz_vpc_1_account_id)
#    session_name       = "AccountLandingZoneSetup"
#    session_expiration = 999
#  }
}

provider "alicloud" {
  alias  = "biz_vpc_2_account"
  region = local.region
#  assume_role {
#    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.biz_vpc_2_account_id)
#    session_name       = "AccountLandingZoneSetup"
#    session_expiration = 999
#  }
}

# unified egress deployment
module "dmz_egress_eip" {
  source    = "../../../terraform-modules/terraform-alicloud-landing-zone-eip"
  providers = {
    alicloud = alicloud.shared_service_account
  }

  eip_config                                    = [
    {
      payment_type     = "PayAsYouGo"
      eip_address_name = "eip-dmz"
      period           = null
      tags             = {
        "Environment" = "shared"
        "Department"  = "ops"
      }
    }
  ]
  create_common_bandwidth_package               = true
  common_bandwidth_package_bandwidth            = 5
  common_bandwidth_package_internet_charge_type = "PayByBandwidth"
}

module "dmz_egress_nat_gateway" {
  source                  = "../../../terraform-modules/terraform-alicloud-landing-zone-nat-gateway"
  providers               = {
    alicloud = alicloud.shared_service_account
  }
  vpc_id                  = local.dmz_vpc_id
  name                    = local.nat_gateway_name
  vswitch_id              = local.vswitch_id_nat_gateway
  association_eip_id_list = module.dmz_egress_eip.eip_id_list

  snat_source_cidr_list = local.snat_source_cidr_list
  snat_ip_list          = module.dmz_egress_eip.eip_address_list
}

module "dmz_egress_biz_vpc_route" {
  source             = "../../../terraform-modules/terraform-alicloud-landing-zone-vpc-custom-route"
  providers          = {
    alicloud = alicloud.biz_vpc_2_account
  }
  vpc_id             = local.biz_vpc_2_id
  create_route_table = false
  route_entry_config = [
    {
      name                  = "dmz-egress"
      destination_cidrblock = "0.0.0.0/0"
      nexthop_type          = "Attachment"
      nexthop_id            = local.cen_attach_id_biz_vpc_2
    }
  ]
}

module "dmz_egress_tr_route" {
  source    = "../../../terraform-modules/terraform-alicloud-landing-zone-cen-custom-route"
  providers = {
    alicloud = alicloud.shared_service_account
  }

  create_route_table                = false
  transit_router_id                 = local.transit_router_id
  transit_router_route_entry_config = [
    {
      route_entry_dest_cidr     = "0.0.0.0/0"
      route_entry_next_hop_type = "Attachment"
      route_entry_name          = "default-to-dmz"
      route_entry_description   = "default-to-dmz"
      route_entry_next_hop_id   = local.cen_attach_id_dmz_vpc
    }
  ]
}


# unified ingress deployment
module "dmz_ingress_alb" {
  source                       = "../../../terraform-modules/terraform-alicloud-landing-zone-alb"
  providers                    = {
    alicloud = alicloud.shared_service_account
  }
  vpc_id                       = local.dmz_vpc_id
  alb_instance_deploy_config   = local.alb_instance_deploy_config
  server_group_backend_servers = local.server_group_backend_servers
}

module "dmz_ingress_dmz_vpc_route" {
  source             = "../../../terraform-modules/terraform-alicloud-landing-zone-vpc-custom-route"
  providers          = {
    alicloud = alicloud.shared_service_account
  }
  vpc_id             = local.dmz_vpc_id
  create_route_table = false
  route_entry_config = [
    {
      name                  = "to-biz-vpc"
      destination_cidrblock = local.biz_vpc_1_cidr
      nexthop_type          = "Attachment"
      nexthop_id            = local.cen_attach_id_dmz_vpc
    }
  ]
}


module "dmz_ingress_biz_vpc_route" {
  source             = "../../../terraform-modules/terraform-alicloud-landing-zone-vpc-custom-route"
  providers          = {
    alicloud = alicloud.shared_service_account
  }
  vpc_id             = local.biz_vpc_1_id
  create_route_table = false
  route_entry_config = local.alb_back_to_source_vpc_route_entry_config
}


module "dmz_ingress_tr_route" {
  source    = "../../../terraform-modules/terraform-alicloud-landing-zone-cen-custom-route"
  providers = {
    alicloud = alicloud.shared_service_account
  }

  create_route_table                = false
  transit_router_id                 = local.transit_router_id
  transit_router_route_entry_config = local.alb_back_to_source_transit_router_route_entry_config
}


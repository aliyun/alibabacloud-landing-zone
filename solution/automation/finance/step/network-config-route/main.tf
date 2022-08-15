locals {
  account_json              = fileexists("../var/account.json") ? jsondecode(file("../var/account.json")) : {}
  shared_service_account_id = var.shared_service_account_id == "" ? local.account_json["shared_service_account_id"] : var.shared_service_account_id
  ops_account_id            = var.ops_account_id == "" ? local.account_json["ops_account_id"] : var.ops_account_id
  dev_account_id            = var.dev_account_id == "" ? local.account_json["dev_account_id"] : var.dev_account_id
  prod_account_id           = var.prod_account_id == "" ? local.account_json["prod_account_id"] : var.prod_account_id

  shared_service_account_vpc_config = var.shared_service_account_vpc_config
  dev_account_vpc_config            = var.dev_account_vpc_config
  prod_account_vpc_config           = var.prod_account_vpc_config
  ops_account_vpc_config            = var.ops_account_vpc_config

  vpc_json                      = fileexists("../var/vpc.json") ? jsondecode(file("../var/vpc.json")) : {}
  shared_service_account_vpc_id = var.shared_service_account_vpc_id == "" ? local.vpc_json["shared_service_account"]["vpc_id"] : var.shared_service_account_vpc_id
  dev_account_vpc_id            = var.dev_account_vpc_id == "" ? local.vpc_json["dev_account"]["vpc_id"] : var.dev_account_vpc_id
  prod_account_vpc_id           = var.prod_account_vpc_id == "" ? local.vpc_json["prod_account"]["vpc_id"] : var.prod_account_vpc_id
  ops_account_vpc_id            = var.ops_account_vpc_id == "" ? local.vpc_json["ops_account"]["vpc_id"] : var.ops_account_vpc_id

  cen_json                                 = fileexists("../var/cen.json") ? jsondecode(file("../var/cen.json")) : {}
  shared_service_account_vpc_attachment_id = var.shared_service_account_vpc_attachment_id == "" ? local.cen_json["shared_service_account"]["attachment_id"] : var.shared_service_account_vpc_attachment_id
  dev_account_vpc_attachment_id            = var.dev_account_vpc_attachment_id == "" ? local.cen_json["dev_account"]["attachment_id"] : var.dev_account_vpc_attachment_id
  prod_account_vpc_attachment_id           = var.prod_account_vpc_attachment_id == "" ? local.cen_json["prod_account"]["attachment_id"] : var.prod_account_vpc_attachment_id
  ops_account_vpc_attachment_id            = var.ops_account_vpc_attachment_id == "" ? local.cen_json["ops_account"]["attachment_id"] : var.ops_account_vpc_attachment_id

  transit_router_id = var.transit_router_id == "" ? local.cen_json["shared_service_account"]["cen_transit_router_id"] : var.transit_router_id

}


provider "alicloud" {
  alias  = "shared_service_account"
  region = local.shared_service_account_vpc_config.region
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.shared_service_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

# Create a custom routing table for business VPC
module "cen_custom_route_business_vpc" {
  source                                    = "../../modules/cen-custom-route"
  providers                                 = {
    alicloud = alicloud.shared_service_account
  }
  transit_router_id                         = local.transit_router_id
  transit_router_route_table_name           = "custom-business-vpc"
  transit_router_association_attachment_ids = [
    local.dev_account_vpc_attachment_id, local.prod_account_vpc_attachment_id
  ]

  transit_router_route_entry_config = [
    {
      route_entry_dest_cidr     = local.shared_service_account_vpc_config.vpc_cidr
      route_entry_next_hop_type = "Attachment"
      route_entry_name          = "biz-vpc-to-dmz-vpc"
      route_entry_description   = "biz-vpc-to-dmz-vpc"
      route_entry_next_hop_id   = local.shared_service_account_vpc_attachment_id
    },{
      route_entry_dest_cidr     = local.dev_account_vpc_config.vpc_cidr
      route_entry_next_hop_type = "BlackHole"
      route_entry_name          = "black-hole-to-dev-vpc"
      route_entry_description   = "black-hole-to-dev-vpc"
      route_entry_next_hop_id   = ""
    },{
      route_entry_dest_cidr     = local.prod_account_vpc_config.vpc_cidr
      route_entry_next_hop_type = "BlackHole"
      route_entry_name          = "black-hole-to-prod-vpc"
      route_entry_description   = "black-hole-to-prod-vpc"
      route_entry_next_hop_id   = ""
    }, {
      route_entry_dest_cidr     = "0.0.0.0/0"
      route_entry_next_hop_type = "Attachment"
      route_entry_name          = "default-to-dmz-vpc"
      route_entry_description   = "default-to-dmz-vpc"
      route_entry_next_hop_id   = local.shared_service_account_vpc_attachment_id
    }
  ]
}

# Create a custom routing table for management VPC
module "cen_custom_route_management_vpc" {
  source                                    = "../../modules/cen-custom-route"
  providers                                 = {
    alicloud = alicloud.shared_service_account
  }
  transit_router_id                         = local.transit_router_id
  transit_router_route_table_name           = "custom-management-vpc"
  transit_router_association_attachment_ids = [
    local.ops_account_vpc_attachment_id
  ]

  transit_router_route_entry_config = [
    {
      route_entry_dest_cidr     = local.shared_service_account_vpc_config.vpc_cidr
      route_entry_next_hop_type = "Attachment"
      route_entry_name          = "management-vpc-to-dmz-vpc"
      route_entry_description   = "management-vpc-to-dmz-vpc"
      route_entry_next_hop_id   = local.shared_service_account_vpc_attachment_id
    }, {
      route_entry_dest_cidr     = "0.0.0.0/0"
      route_entry_next_hop_type = "Attachment"
      route_entry_name          = "default-to-dmz-vpc"
      route_entry_description   = "default-to-dmz-vpc"
      route_entry_next_hop_id   = local.shared_service_account_vpc_attachment_id
    }
  ]
}

# Save custom route table information
resource "local_file" "route_json" {
  content  = templatefile("../var/route.json.tmpl", {
    cen_custom_route_table_id_business_vpc   = module.cen_custom_route_business_vpc.route_table_id
    cen_custom_route_table_id_management_vpc = module.cen_custom_route_management_vpc.route_table_id
  })
  filename = "../var/route.json"
}


# custom route for dmz vpc
module "shared_service_account_vpc_custom_route" {
  source             = "../../modules/vpc-custom-route"
  providers          = {
    alicloud = alicloud.shared_service_account
  }
  vpc_id             = local.shared_service_account_vpc_id
  route_entry_config = [
    {
      destination_cidrblock = var.all_vpc_cidr
      nexthop_type          = "Attachment"
      nexthop_id            = local.shared_service_account_vpc_attachment_id
    }
  ]
}

provider "alicloud" {
  alias  = "dev_account"
  region = local.dev_account_vpc_config.region
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.dev_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

# custom route for dev vpc
module "dev_account_vpc_custom_route" {
  source             = "../../modules/vpc-custom-route"
  providers          = {
    alicloud = alicloud.dev_account
  }
  vpc_id             = local.dev_account_vpc_id
  route_entry_config = [
    {
      destination_cidrblock = "0.0.0.0/0"
      nexthop_type          = "Attachment"
      nexthop_id            = local.dev_account_vpc_attachment_id
    }
  ]
}


provider "alicloud" {
  alias  = "prod_account"
  region = local.prod_account_vpc_config.region
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.prod_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

# custom route for prod vpc
module "prod_account_vpc_custom_route" {
  source             = "../../modules/vpc-custom-route"
  providers          = {
    alicloud = alicloud.prod_account
  }
  vpc_id             = local.prod_account_vpc_id
  route_entry_config = [
    {
      destination_cidrblock = "0.0.0.0/0"
      nexthop_type          = "Attachment"
      nexthop_id            = local.prod_account_vpc_attachment_id
    }
  ]
}


provider "alicloud" {
  alias  = "ops_account"
  region = local.ops_account_vpc_config.region
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.ops_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

# custom route for management vpc in ops account
module "ops_account_vpc_custom_route" {
  source             = "../../modules/vpc-custom-route"
  providers          = {
    alicloud = alicloud.ops_account
  }
  vpc_id             = local.ops_account_vpc_id
  route_entry_config = [
    {
      destination_cidrblock = "0.0.0.0/0"
      nexthop_type          = "Attachment"
      nexthop_id            = local.ops_account_vpc_attachment_id
    }
  ]
}
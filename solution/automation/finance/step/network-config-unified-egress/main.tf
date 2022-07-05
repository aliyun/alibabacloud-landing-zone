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

  cen_json                      = fileexists("../var/cen.json") ? jsondecode(file("../var/cen.json")) : {}
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

data "alicloud_cen_transit_router_route_tables" "tr_route_table" {
  provider          = alicloud.shared_service_account
  transit_router_id = local.transit_router_id
}

resource "alicloud_cen_transit_router_route_entry" "tr_route_entry_default" {
  provider                                          = alicloud.shared_service_account
  transit_router_route_table_id                     = data.alicloud_cen_transit_router_route_tables.tr_route_table.ids.0
  transit_router_route_entry_destination_cidr_block = "0.0.0.0/0"
  transit_router_route_entry_next_hop_type          = "Attachment"
  transit_router_route_entry_name                   = "to-dmz-vpc"
  transit_router_route_entry_description            = "to-dmz-vpc"
  transit_router_route_entry_next_hop_id            = local.shared_service_account_vpc_attachment_id
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

data "alicloud_route_tables" "dev_account_vpc_route_tables" {
  provider = alicloud.dev_account
  vpc_id   = local.dev_account_vpc_id
}

resource "alicloud_route_entry" "dev_account_vpc_route_entry" {
  provider              = alicloud.dev_account
  route_table_id        = data.alicloud_route_tables.dev_account_vpc_route_tables.ids.0
  destination_cidrblock = "0.0.0.0/0"
  nexthop_type          = "Attachment"
  nexthop_id            = local.dev_account_vpc_attachment_id
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

data "alicloud_route_tables" "prod_account_vpc_route_tables" {
  provider = alicloud.prod_account
  vpc_id   = local.prod_account_vpc_id
}

resource "alicloud_route_entry" "prod_account_vpc_route_entry" {
  provider              = alicloud.prod_account
  route_table_id        = data.alicloud_route_tables.prod_account_vpc_route_tables.ids.0
  destination_cidrblock = "0.0.0.0/0"
  nexthop_type          = "Attachment"
  nexthop_id            = local.prod_account_vpc_attachment_id
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

data "alicloud_route_tables" "ops_account_vpc_route_tables" {
  provider = alicloud.ops_account
  vpc_id   = local.ops_account_vpc_id
}

resource "alicloud_route_entry" "ops_account_vpc_route_entry" {
  provider              = alicloud.ops_account
  route_table_id        = data.alicloud_route_tables.ops_account_vpc_route_tables.ids.0
  destination_cidrblock = "0.0.0.0/0"
  nexthop_type          = "Attachment"
  nexthop_id            = local.ops_account_vpc_attachment_id
}
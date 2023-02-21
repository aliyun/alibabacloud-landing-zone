data "alicloud_account" "current" {
}
locals {
  role_name = "ResourceDirectoryAccountAccessRole"
  user1_is_admin = var.user1_id == data.alicloud_account.current.id ? true : false
  user2_is_admin = var.user2_id == data.alicloud_account.current.id ? true : false
}
# provider
provider "alicloud" {
  alias = "user1"
  region = var.region
  assume_role {
    role_arn = local.user1_is_admin ? null : format("acs:ram::%s:role/%s", var.user1_id, local.role_name)
    session_name = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}


provider "alicloud" {
  alias = "user2"
  region = var.region
  assume_role {
    role_arn = local.user2_is_admin ? null : format("acs:ram::%s:role/%s", var.user2_id, local.role_name)
    session_name = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

module "user1_vpc" {
  source = "vpc"
  providers = {alicloud: alicloud.user1}
  vpc_cidr = var.user1_vpc_cidr
  pod_vsw_cidr = var.user1_pod_vsw_cidr
  node_vsw_cidr = var.user1_node_vsw_cidr
  zone_id = var.zone_id
}


module "user2_vpc" {
  source = "vpc"
  providers = {alicloud: alicloud.user2}
  vpc_cidr = var.user2_vpc_cidr
  pod_vsw_cidr = var.user2_pod_vsw_cidr
  node_vsw_cidr = var.user2_node_vsw_cidr
  zone_id = var.zone_id
}


resource "alicloud_cen_instance" "cen" {
  provider = alicloud.user1
  cen_instance_name = "云上企业网络"
}

resource "alicloud_cen_transit_router" "tr" {
  provider = alicloud.user1
  cen_id = alicloud_cen_instance.cen.id
}

resource "alicloud_cen_instance_grant" "grant" {
  provider          = alicloud.user2
  cen_id            = alicloud_cen_instance.cen.id
  child_instance_id = module.user2_vpc.vpc_id
  cen_owner_id      = var.user1_id
}

resource "alicloud_cen_transit_router_vpc_attachment" "vpc_att" {
  provider = alicloud.user1
  count = 2
  transit_router_attachment_name = format("vpc_attachment_%s", count.index)
  cen_id            = alicloud_cen_instance.cen.id
  transit_router_id = alicloud_cen_transit_router.tr.transit_router_id
  vpc_id            = [module.user1_vpc.vpc_id, module.user2_vpc.vpc_id][count.index]
  vpc_owner_id = count.index == 0? null : var.user2_id
  zone_mappings {
    zone_id    = var.zone_id
    vswitch_id = [module.user1_vpc.pod_vsw_id, module.user2_vpc.pod_vsw_id][count.index]
  }
  depends_on = [alicloud_cen_instance_grant.grant]
}

resource "alicloud_route_entry" "route_entry1" {
  provider = alicloud.user1
  for_each = toset([var.user1_vpc_cidr, var.user2_vpc_cidr])
  route_table_id        = module.user1_vpc.route_table_id
  destination_cidrblock = each.key
  nexthop_type          = "Attachment"
  nexthop_id            = alicloud_cen_transit_router_vpc_attachment.vpc_att[0].transit_router_attachment_id
}

resource "alicloud_route_entry" "route_entry2" {
  provider = alicloud.user2
  for_each = toset([var.user1_vpc_cidr, var.user2_vpc_cidr])
  route_table_id        = module.user2_vpc.route_table_id
  destination_cidrblock = each.key
  nexthop_type          = "Attachment"
  nexthop_id            = alicloud_cen_transit_router_vpc_attachment.vpc_att[1].transit_router_attachment_id
}

resource "alicloud_cen_transit_router_route_table" "route_table" {
  provider = alicloud.user1
  transit_router_id = alicloud_cen_transit_router.tr.transit_router_id
}

resource "alicloud_cen_transit_router_route_entry" "route_entry" {
  count = 2
  provider = alicloud.user1
  transit_router_route_table_id                     = alicloud_cen_transit_router_route_table.route_table.transit_router_route_table_id
  transit_router_route_entry_destination_cidr_block = [var.user1_vpc_cidr, var.user2_vpc_cidr][count.index]
  transit_router_route_entry_next_hop_type          = "Attachment"
  transit_router_route_entry_next_hop_id            = alicloud_cen_transit_router_vpc_attachment.vpc_att.*.transit_router_attachment_id[count.index]
}

resource "alicloud_cen_transit_router_route_table_association" "association" {
  provider = alicloud.user1
  count = 2
  transit_router_route_table_id = alicloud_cen_transit_router_route_table.route_table.transit_router_route_table_id
  transit_router_attachment_id  = alicloud_cen_transit_router_vpc_attachment.vpc_att.*.transit_router_attachment_id[count.index]
}


module "user1_k8s" {
  source = "k8s"
  providers = {alicloud: alicloud.user1}
  pod_vsw_id = module.user1_vpc.pod_vsw_id
  node_vsw_id = module.user1_vpc.node_vsw_id
  service_cidr = var.user1_service_cidr
}

module "user2_k8s" {
  source = "k8s"
  providers = {alicloud: alicloud.user2}
  pod_vsw_id = module.user2_vpc.pod_vsw_id
  node_vsw_id = module.user2_vpc.node_vsw_id
  service_cidr = var.user2_service_cidr
}

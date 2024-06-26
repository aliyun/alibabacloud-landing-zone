data "alicloud_account" "current" {
}
locals {
  role_name = var.role_name
  user1_id = var.use_resource_directory && var.user1_id != "" ? var.user1_id : data.alicloud_account.current.id
  user2_id = var.use_resource_directory ? var.user2_id : var.user2_id_not_from_rd
  user1_is_admin = local.user1_id == data.alicloud_account.current.id ? true : false
  user2_is_admin = local.user2_id == data.alicloud_account.current.id ? true : false
}
# provider
provider "alicloud" {
  alias = "user1"
  region = var.region
  assume_role {
    role_arn = local.user1_is_admin ? null : format("acs:ram::%s:role/%s", local.user1_id, local.role_name)
    session_name = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

provider "alicloud" {
  alias = "user2"
  region = var.region
  assume_role {
    role_arn = local.user2_is_admin ? null : format("acs:ram::%s:role/%s", local.user2_id, local.role_name)
    session_name = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

module "user1_vpc" {
  count = 2
  source = "./vpc"
  providers = {alicloud: alicloud.user1}
  vpc_cidr = [var.vpc1_cidr, var.vpc2_cidr][count.index]
  vsw_cidr = [var.vsw1_cidr, var.vsw2_cidr][count.index]
  zone_id = var.zone_id
}

module "user2_vpc" {
  count = 2
  source = "./vpc"
  providers = {alicloud: alicloud.user2}
  vpc_cidr = [var.vpc3_cidr, var.vpc4_cidr][count.index]
  vsw_cidr = [var.vsw3_cidr, var.vsw4_cidr][count.index]
  zone_id = var.zone_id
}

resource "alicloud_cen_instance" "cen" {
  provider = alicloud.user2
  cen_instance_name = "云上企业网络"
}

resource "alicloud_cen_transit_router" "tr" {
  provider = alicloud.user2
  cen_id = alicloud_cen_instance.cen.id
}

locals {
  vpc_ids = concat(module.user1_vpc.*.vpc_id, module.user2_vpc.*.vpc_id)
  vsw_ids = concat(module.user1_vpc.*.vsw_id, module.user2_vpc.*.vsw_id)
}

resource "alicloud_cen_instance_grant" "grant" {
  count = 2
  provider          = alicloud.user1
  cen_id            = alicloud_cen_instance.cen.id
  child_instance_id = local.vpc_ids[count.index]
  cen_owner_id      = local.user2_id
}

resource "alicloud_cen_transit_router_vpc_attachment" "vpc_att" {
  provider = alicloud.user2
  count = 4
  transit_router_attachment_name = format("vpc_attachment_%s", count.index+1)
  cen_id            = alicloud_cen_instance.cen.id
  transit_router_id = alicloud_cen_transit_router.tr.transit_router_id
  vpc_id            = local.vpc_ids[count.index]
  vpc_owner_id = count.index < 2? local.user1_id : null
  zone_mappings {
    zone_id    = var.zone_id
    vswitch_id = local.vsw_ids[count.index]
  }
  depends_on = [alicloud_cen_instance_grant.grant]
}

resource "alicloud_vpc_peer_connection" "peer" {
  provider = alicloud.user1
  vpc_id               = module.user1_vpc[1].vpc_id
  accepting_ali_uid    = local.user2_id
  accepting_region_id  = var.region
  accepting_vpc_id     = module.user2_vpc[1].vpc_id
}

resource "alicloud_vpc_peer_connection_accepter" "default" {
  provider = alicloud.user2
  instance_id = alicloud_vpc_peer_connection.peer.id
}

module "user1_ecs" {
  count = 2
  source = "./ecs"
  providers = {alicloud: alicloud.user1}
  create_ecs = var.create_ecs
  vpc_id = module.user1_vpc[count.index].vpc_id
  vsw_id = module.user1_vpc[count.index].vsw_id
  zone_id = var.zone_id
  instance_type = var.instance_type
  system_disk_category = var.system_disk_category
  ecs_password = var.ecs_password
  instance_name = format("test_ecs%s",count.index+1)
}

module "user2_ecs" {
  count = 2
  source = "./ecs"
  providers = {alicloud: alicloud.user2}
  create_ecs = var.create_ecs
  vpc_id = module.user2_vpc[count.index].vpc_id
  vsw_id = module.user2_vpc[count.index].vsw_id
  zone_id = var.zone_id
  instance_type = var.instance_type
  system_disk_category = var.system_disk_category
  ecs_password = var.ecs_password
  instance_name = format("test_ecs%s",count.index+3)
}

locals {
  tr_attachment_ids = alicloud_cen_transit_router_vpc_attachment.vpc_att.*.transit_router_attachment_id
}

resource "alicloud_route_entry" "route_entry1" {
  provider = alicloud.user1
  for_each = toset([var.vpc1_cidr, var.vpc2_cidr, var.vpc3_cidr, var.vpc4_cidr])
  route_table_id        = module.user1_vpc[0].route_table_id
  destination_cidrblock = each.key
  nexthop_type          = "Attachment"
  nexthop_id            = local.tr_attachment_ids[0]
}

resource "alicloud_route_entry" "route_entry2" {
  provider = alicloud.user1
  for_each = toset([var.vpc1_cidr, var.vpc2_cidr, var.vpc3_cidr])
  route_table_id        = module.user1_vpc[1].route_table_id
  destination_cidrblock = each.key
  nexthop_type          = "Attachment"
  nexthop_id            = local.tr_attachment_ids[1]
}

resource "alicloud_route_entry" "route_entry3" {
  provider = alicloud.user2
  for_each = toset([var.vpc1_cidr, var.vpc2_cidr, var.vpc3_cidr, var.vpc4_cidr])
  route_table_id        = module.user2_vpc[0].route_table_id
  destination_cidrblock = each.key
  nexthop_type          = "Attachment"
  nexthop_id            = local.tr_attachment_ids[2]
}

resource "alicloud_route_entry" "route_entry4" {
  provider = alicloud.user2
  for_each = toset([var.vpc1_cidr, var.vpc3_cidr, var.vpc4_cidr])
  route_table_id        = module.user2_vpc[1].route_table_id
  destination_cidrblock = each.key
  nexthop_type          = "Attachment"
  nexthop_id            = local.tr_attachment_ids[3]
}

resource "alicloud_route_entry" "route_entry_peer2" {
  provider = alicloud.user1
  route_table_id        = module.user1_vpc[1].route_table_id
  destination_cidrblock = var.vpc4_cidr
  nexthop_type          = "VpcPeer"
  nexthop_id            = alicloud_vpc_peer_connection.peer.id
  depends_on = [alicloud_vpc_peer_connection_accepter.default]
}

resource "alicloud_route_entry" "route_entry_peer4" {
  provider = alicloud.user2
  route_table_id        = module.user2_vpc[1].route_table_id
  destination_cidrblock = var.vpc2_cidr
  nexthop_type          = "VpcPeer"
  nexthop_id            = alicloud_vpc_peer_connection.peer.id
  depends_on = [alicloud_vpc_peer_connection_accepter.default]
}

resource "alicloud_cen_transit_router_route_table" "route_table" {
  provider = alicloud.user2
  transit_router_id = alicloud_cen_transit_router.tr.transit_router_id
}

resource "alicloud_cen_transit_router_route_entry" "route_entry1" {
  provider = alicloud.user2
  count = 4
  transit_router_route_table_id                     = alicloud_cen_transit_router_route_table.route_table.transit_router_route_table_id
  transit_router_route_entry_destination_cidr_block = [var.vpc1_cidr, var.vpc2_cidr, var.vpc3_cidr, var.vpc4_cidr][count.index]
  transit_router_route_entry_next_hop_type          = "Attachment"
  transit_router_route_entry_next_hop_id            = local.tr_attachment_ids[count.index]
}

resource "alicloud_cen_transit_router_route_table_association" "association" {
  provider = alicloud.user2
  count = 4
  transit_router_route_table_id = alicloud_cen_transit_router_route_table.route_table.transit_router_route_table_id
  transit_router_attachment_id  = local.tr_attachment_ids[count.index]
}

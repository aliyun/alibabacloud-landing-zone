# provider
provider "alicloud" {
  alias = "user1_region1"
  region     = var.region1
  assume_role {
    role_arn           = format("acs:ram::%s:role/%s", var.user1_id, local.role_name)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

provider "alicloud" {
  alias = "user1_region2"
  region     = var.region2
  assume_role {
    role_arn           = format("acs:ram::%s:role/%s", var.user1_id, local.role_name)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

provider "alicloud" {
  alias = "user2_region1"
  region     = var.region1
  assume_role {
    role_arn           = format("acs:ram::%s:role/%s", var.user2_id, local.role_name)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

provider "alicloud" {
  alias = "user2_region2"
  region     = var.region2
  assume_role {
    role_arn           = format("acs:ram::%s:role/%s", var.user2_id, local.role_name)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}


# user1 region1
resource "alicloud_vpc" "user1_region1_vpc" {
  provider = alicloud.user1_region1
  vpc_name       = "基础服务VPC"
  cidr_block = var.user1_region1_vpc_cidr_block
}

resource "alicloud_vswitch" "user1_region1_vsw" {
  provider = alicloud.user1_region1
  vpc_id     = alicloud_vpc.user1_region1_vpc.id
  cidr_block = var.user1_region1_vsw_cidr_block
  zone_id    = var.region1_zone_id
}

resource "alicloud_security_group" "user1_region1_group" {
  provider = alicloud.user1_region1
  count = var.create_ecs? 1:0
  vpc_id      = alicloud_vpc.user1_region1_vpc.id
}

resource "alicloud_security_group_rule" "user1_region1_rule" {
  provider = alicloud.user1_region1
  count = var.create_ecs? 1:0
  type              = "ingress"
  ip_protocol       = "all"
  nic_type          = "intranet"
  policy            = "accept"
  port_range        = "1/65535"
  priority          = 1
  security_group_id = alicloud_security_group.user1_region1_group[0].id
  cidr_ip           = "0.0.0.0/0"
}

resource "alicloud_instance" "user1_region1_ecs" {
  provider = alicloud.user1_region1
  count = var.create_ecs? 1:0
  availability_zone = var.region1_zone_id
  security_groups   = alicloud_security_group.user1_region1_group[0].*.id
  instance_type              = var.region1_instance_type
  system_disk_category       = var.region1_system_disk_category
  image_id                   = "centos_7_9_x64_20G_alibase_20220824.vhd"
  instance_name              = "test_ecs"
  vswitch_id                 = alicloud_vswitch.user1_region1_vsw.id
  password                   = var.ecs_password
}

resource "alicloud_cen_instance" "user1_cen" {
  provider = alicloud.user1_region1
  cen_instance_name = "云上企业网络-集团"
}

resource "alicloud_cen_transit_router" "user1_region1_tr" {
  provider = alicloud.user1_region1
  cen_id              = alicloud_cen_instance.user1_cen.id
}

resource "alicloud_cen_transit_router_vpc_attachment" "user1_region1_vpc_att" {
  provider = alicloud.user1_region1
  cen_id            = alicloud_cen_instance.user1_cen.id
  transit_router_id = alicloud_cen_transit_router.user1_region1_tr.transit_router_id
  vpc_id            = alicloud_vpc.user1_region1_vpc.id
  zone_mappings {
    zone_id    = var.region1_zone_id
    vswitch_id = alicloud_vswitch.user1_region1_vsw.id
  }
}

resource "alicloud_cen_transit_router_route_table" "user1_region1_route_table" {
  provider = alicloud.user1_region1
  transit_router_id = alicloud_cen_transit_router.user1_region1_tr.transit_router_id
}

resource "alicloud_cen_transit_router_route_table_association" "user1_region1_table_association" {
  provider = alicloud.user1_region1
  transit_router_route_table_id = alicloud_cen_transit_router_route_table.user1_region1_route_table.transit_router_route_table_id
  transit_router_attachment_id  = alicloud_cen_transit_router_vpc_attachment.user1_region1_vpc_att.transit_router_attachment_id
}

resource "alicloud_cen_transit_router_route_table_propagation" "user1_region1_table_propagation" {
  provider = alicloud.user1_region1
  transit_router_route_table_id = alicloud_cen_transit_router_route_table.user1_region1_route_table.transit_router_route_table_id
  transit_router_attachment_id  = alicloud_cen_transit_router_vpc_attachment.user1_region1_vpc_att.transit_router_attachment_id
}

resource "alicloud_route_entry" "user1_region1_route_entry" {
  provider = alicloud.user1_region1
  for_each = toset([var.user1_region1_vpc_cidr_block, var.user1_region2_vpc_cidr_block,
  var.user2_region1_vpc_cidr_block, var.user2_region2_vpc_cidr_block, var.user2_connect_vpc_cidr_block])
  route_table_id        = alicloud_vpc.user1_region1_vpc.route_table_id
  destination_cidrblock = each.key
  nexthop_type          = "Attachment"
  nexthop_id            = alicloud_cen_transit_router_vpc_attachment.user1_region1_vpc_att.transit_router_attachment_id
}


# user1 region2
resource "alicloud_vpc" "user1_region2_vpc" {
  provider = alicloud.user1_region2
  vpc_name       = "基础服务VPC"
  cidr_block = var.user1_region2_vpc_cidr_block
}

resource "alicloud_vswitch" "user1_region2_vsw" {
  provider = alicloud.user1_region2
  vpc_id     = alicloud_vpc.user1_region2_vpc.id
  cidr_block = var.user1_region2_vsw_cidr_block
  zone_id    = var.region2_zone_id
}

resource "alicloud_security_group" "user1_region2_group" {
  provider = alicloud.user1_region2
  count = var.create_ecs? 1:0
  vpc_id      = alicloud_vpc.user1_region2_vpc.id
}

resource "alicloud_security_group_rule" "user1_region2_rule" {
  provider = alicloud.user1_region2
  count = var.create_ecs? 1:0
  type              = "ingress"
  ip_protocol       = "all"
  nic_type          = "intranet"
  policy            = "accept"
  port_range        = "1/65535"
  priority          = 1
  security_group_id = alicloud_security_group.user1_region2_group[0].id
  cidr_ip           = "0.0.0.0/0"
}

resource "alicloud_instance" "user1_region2_ecs" {
  provider = alicloud.user1_region2
  count = var.create_ecs? 1:0
  availability_zone = var.region2_zone_id
  security_groups   = alicloud_security_group.user1_region2_group[0].*.id
  instance_type              = var.region2_instance_type
  system_disk_category       = var.region2_system_disk_category
  image_id                   = "centos_7_9_x64_20G_alibase_20220824.vhd"
  instance_name              = "test_ecs"
  vswitch_id                 = alicloud_vswitch.user1_region2_vsw.id
  password                   = var.ecs_password
}

resource "alicloud_cen_transit_router" "user1_region2_tr" {
  provider = alicloud.user1_region2
  cen_id              = alicloud_cen_instance.user1_cen.id
}

resource "alicloud_cen_transit_router_vpc_attachment" "user1_region2_vpc_att1" {
  provider = alicloud.user1_region2
  cen_id            = alicloud_cen_instance.user1_cen.id
  transit_router_id = alicloud_cen_transit_router.user1_region2_tr.transit_router_id
  vpc_id            = alicloud_vpc.user1_region2_vpc.id
  zone_mappings {
    zone_id    = var.region2_zone_id
    vswitch_id = alicloud_vswitch.user1_region2_vsw.id
  }
}

resource "alicloud_cen_transit_router_route_table" "user1_region2_route_table" {
  provider = alicloud.user1_region2
  transit_router_id = alicloud_cen_transit_router.user1_region2_tr.transit_router_id
}

resource "alicloud_cen_transit_router_route_table_association" "user1_region2_association1" {
  provider = alicloud.user1_region2
  transit_router_route_table_id = alicloud_cen_transit_router_route_table.user1_region2_route_table.transit_router_route_table_id
  transit_router_attachment_id  = alicloud_cen_transit_router_vpc_attachment.user1_region2_vpc_att1.transit_router_attachment_id
}

resource "alicloud_cen_transit_router_route_table_propagation" "user1_region2_propagation1" {
  provider = alicloud.user1_region2
  transit_router_route_table_id = alicloud_cen_transit_router_route_table.user1_region2_route_table.transit_router_route_table_id
  transit_router_attachment_id  = alicloud_cen_transit_router_vpc_attachment.user1_region2_vpc_att1.transit_router_attachment_id
}


resource "alicloud_route_entry" "user1_region2_route_entry" {
  provider = alicloud.user1_region2
  for_each = toset([var.user1_region1_vpc_cidr_block, var.user1_region2_vpc_cidr_block,
  var.user2_region1_vpc_cidr_block, var.user2_region2_vpc_cidr_block, var.user2_connect_vpc_cidr_block])
  route_table_id        = alicloud_vpc.user1_region2_vpc.route_table_id
  destination_cidrblock = each.key
  nexthop_type          = "Attachment"
  nexthop_id            = alicloud_cen_transit_router_vpc_attachment.user1_region2_vpc_att1.transit_router_attachment_id
}

resource "alicloud_cen_transit_router_peer_attachment" "user1_peer_attachment" {
  provider = alicloud.user1_region2
  cen_id                                = alicloud_cen_instance.user1_cen.id
  transit_router_id                     = alicloud_cen_transit_router.user1_region2_tr.transit_router_id
  peer_transit_router_region_id         = var.region1
  peer_transit_router_id                = alicloud_cen_transit_router.user1_region1_tr.transit_router_id
  auto_publish_route_enabled  = true
}

resource "alicloud_cen_transit_router_route_table_association" "user1_region2_association2" {
  provider = alicloud.user1_region2
  transit_router_route_table_id = alicloud_cen_transit_router_route_table.user1_region2_route_table.transit_router_route_table_id
  transit_router_attachment_id  = alicloud_cen_transit_router_peer_attachment.user1_peer_attachment.transit_router_attachment_id
}

resource "alicloud_cen_transit_router_route_table_propagation" "user1_region2_propagation2" {
  provider = alicloud.user1_region2
  transit_router_route_table_id = alicloud_cen_transit_router_route_table.user1_region2_route_table.transit_router_route_table_id
  transit_router_attachment_id  = alicloud_cen_transit_router_peer_attachment.user1_peer_attachment.transit_router_attachment_id
}

resource "alicloud_cen_transit_router_route_table_association" "user1_region2_association3" {
  provider = alicloud.user1_region2
  transit_router_route_table_id = alicloud_cen_transit_router_route_table.user1_region1_route_table.transit_router_route_table_id
  transit_router_attachment_id  = alicloud_cen_transit_router_peer_attachment.user1_peer_attachment.transit_router_attachment_id
}

resource "alicloud_cen_transit_router_route_table_propagation" "uesr1_region2_propagation3" {
  provider = alicloud.user1_region2
  transit_router_route_table_id = alicloud_cen_transit_router_route_table.user1_region1_route_table.transit_router_route_table_id
  transit_router_attachment_id  = alicloud_cen_transit_router_peer_attachment.user1_peer_attachment.transit_router_attachment_id
}


# user2 region1
resource "alicloud_vpc" "user2_region1_vpc" {
  provider = alicloud.user2_region1
  vpc_name       = "基础服务VPC"
  cidr_block = var.user2_region1_vpc_cidr_block
}

resource "alicloud_vswitch" "user2_region1_vsw" {
  provider = alicloud.user2_region1
  vpc_id     = alicloud_vpc.user2_region1_vpc.id
  cidr_block = var.user2_region1_vsw_cidr_block
  zone_id    = var.region1_zone_id
}

resource "alicloud_security_group" "user2_region1_group" {
  provider = alicloud.user2_region1
  count = var.create_ecs? 1:0
  vpc_id      = alicloud_vpc.user2_region1_vpc.id
}

resource "alicloud_security_group_rule" "user2_region1_rule" {
  provider = alicloud.user2_region1
  count = var.create_ecs? 1:0
  type              = "ingress"
  ip_protocol       = "all"
  nic_type          = "intranet"
  policy            = "accept"
  port_range        = "1/65535"
  priority          = 1
  security_group_id = alicloud_security_group.user2_region1_group[0].id
  cidr_ip           = "0.0.0.0/0"
}

resource "alicloud_instance" "user2_region1_ecs" {
  provider = alicloud.user2_region1
  count = var.create_ecs? 1:0
  availability_zone = var.region1_zone_id
  security_groups   = alicloud_security_group.user2_region1_group[0].*.id
  instance_type              = var.region1_instance_type
  system_disk_category       = var.region1_system_disk_category
  image_id                   = "centos_7_9_x64_20G_alibase_20220824.vhd"
  instance_name              = "test_ecs"
  vswitch_id                 = alicloud_vswitch.user2_region1_vsw.id
  password                   = var.ecs_password
}

resource "alicloud_cen_instance" "user2_cen" {
  provider = alicloud.user2_region1
  cen_instance_name = "云上企业网络-集团"
}

resource "alicloud_cen_transit_router" "user2_region1_tr" {
  provider = alicloud.user2_region1
  cen_id              = alicloud_cen_instance.user2_cen.id
}

resource "alicloud_cen_transit_router_vpc_attachment" "user2_region1_vpc_att" {
  provider = alicloud.user2_region1
  cen_id            = alicloud_cen_instance.user2_cen.id
  transit_router_id = alicloud_cen_transit_router.user2_region1_tr.transit_router_id
  vpc_id            = alicloud_vpc.user2_region1_vpc.id
  zone_mappings {
    zone_id    = var.region1_zone_id
    vswitch_id = alicloud_vswitch.user2_region1_vsw.id
  }
}

resource "alicloud_cen_transit_router_route_table" "user2_region1_route_table" {
  provider = alicloud.user2_region1
  transit_router_id = alicloud_cen_transit_router.user2_region1_tr.transit_router_id
}

resource "alicloud_cen_transit_router_route_table_association" "user2_region1_association" {
  provider = alicloud.user2_region1
  transit_router_route_table_id = alicloud_cen_transit_router_route_table.user2_region1_route_table.transit_router_route_table_id
  transit_router_attachment_id  = alicloud_cen_transit_router_vpc_attachment.user2_region1_vpc_att.transit_router_attachment_id
}

resource "alicloud_cen_transit_router_route_table_propagation" "user2_region1_propagation" {
  provider = alicloud.user2_region1
  transit_router_route_table_id = alicloud_cen_transit_router_route_table.user2_region1_route_table.transit_router_route_table_id
  transit_router_attachment_id  = alicloud_cen_transit_router_vpc_attachment.user2_region1_vpc_att.transit_router_attachment_id
}

resource "alicloud_route_entry" "user2_region1_route_entry" {
  provider = alicloud.user2_region1
  for_each = toset([var.user1_region1_vpc_cidr_block, var.user1_region2_vpc_cidr_block,
  var.user2_region1_vpc_cidr_block, var.user2_region2_vpc_cidr_block, var.user2_connect_vpc_cidr_block])
  route_table_id        = alicloud_vpc.user2_region1_vpc.route_table_id
  destination_cidrblock = each.key
  nexthop_type          = "Attachment"
  nexthop_id            = alicloud_cen_transit_router_vpc_attachment.user2_region1_vpc_att.transit_router_attachment_id
}


# user2 region2
resource "alicloud_vpc" "user2_region2_vpc" {
  provider = alicloud.user2_region2
  vpc_name       = "基础服务VPC"
  cidr_block = var.user2_region2_vpc_cidr_block
}

resource "alicloud_vswitch" "user2_region2_vsw" {
  provider = alicloud.user2_region2
  vpc_id     = alicloud_vpc.user2_region2_vpc.id
  cidr_block = var.user2_region2_vsw_cidr_block
  zone_id    = var.region2_zone_id
}

resource "alicloud_vpc" "user2_connect_vpc" {
  provider = alicloud.user2_region2
  vpc_name       = "数据互通VPC"
  cidr_block = var.user2_connect_vpc_cidr_block
}

resource "alicloud_vswitch" "user2_connect_vsw" {
  provider = alicloud.user2_region2
  vpc_id     = alicloud_vpc.user2_connect_vpc.id
  cidr_block = var.user2_connect_vsw_cidr_block
  zone_id    = var.region2_zone_id
}

resource "alicloud_security_group" "user2_region2_group1" {
  provider = alicloud.user2_region2
  count = var.create_ecs? 1:0
  vpc_id      = alicloud_vpc.user2_region2_vpc.id
}

resource "alicloud_security_group" "user2_region2_group2" {
  provider = alicloud.user2_region2
  count = var.create_ecs? 1:0
  vpc_id      = alicloud_vpc.user2_connect_vpc.id
}

resource "alicloud_security_group_rule" "user2_region2_rule1" {
  provider = alicloud.user2_region2
  count = var.create_ecs? 1:0
  type              = "ingress"
  ip_protocol       = "all"
  nic_type          = "intranet"
  policy            = "accept"
  port_range        = "1/65535"
  priority          = 1
  security_group_id = alicloud_security_group.user2_region2_group1[0].id
  cidr_ip           = "0.0.0.0/0"
}

resource "alicloud_security_group_rule" "user2_region2_rule2" {
  provider = alicloud.user2_region2
  count = var.create_ecs? 1:0
  type              = "ingress"
  ip_protocol       = "all"
  nic_type          = "intranet"
  policy            = "accept"
  port_range        = "1/65535"
  priority          = 1
  security_group_id = alicloud_security_group.user2_region2_group2[0].id
  cidr_ip           = "0.0.0.0/0"
}

resource "alicloud_instance" "user2_region2_ecs1" {
  provider = alicloud.user2_region2
  count = var.create_ecs? 1:0
  availability_zone = var.region2_zone_id
  security_groups   = alicloud_security_group.user2_region2_group1[0].*.id
  instance_type              = var.region2_instance_type
  system_disk_category       = var.region2_system_disk_category
  image_id                   = "centos_7_9_x64_20G_alibase_20220824.vhd"
  instance_name              = "test_ecs1"
  vswitch_id                 = alicloud_vswitch.user2_region2_vsw.id
  password                   = var.ecs_password
}

resource "alicloud_instance" "user2_region2_ecs2" {
  provider = alicloud.user2_region2
  count = var.create_ecs? 1:0
  availability_zone = var.region2_zone_id
  security_groups   = alicloud_security_group.user2_region2_group2[0].*.id
  instance_type              = var.region2_instance_type
  system_disk_category       = var.region2_system_disk_category
  image_id                   = "centos_7_9_x64_20G_alibase_20220824.vhd"
  instance_name              = "test_ecs2"
  vswitch_id                 = alicloud_vswitch.user2_connect_vsw.id
  password                   = var.ecs_password
}

resource "alicloud_cen_transit_router" "user2_region2_tr" {
  provider = alicloud.user2_region2
  cen_id              = alicloud_cen_instance.user2_cen.id
}

resource "alicloud_cen_transit_router_vpc_attachment" "user2_region2_vpc_att1" {
  provider = alicloud.user2_region2
  cen_id            = alicloud_cen_instance.user2_cen.id
  transit_router_id = alicloud_cen_transit_router.user2_region2_tr.transit_router_id
  vpc_id            = alicloud_vpc.user2_region2_vpc.id
  zone_mappings {
    zone_id    = var.region2_zone_id
    vswitch_id = alicloud_vswitch.user2_region2_vsw.id
  }
}

resource "alicloud_cen_transit_router_vpc_attachment" "user2_region2_vpc_att2" {
  provider = alicloud.user2_region2
  cen_id            = alicloud_cen_instance.user2_cen.id
  transit_router_id = alicloud_cen_transit_router.user2_region2_tr.transit_router_id
  vpc_id            = alicloud_vpc.user2_connect_vpc.id
  zone_mappings {
    zone_id    = var.region2_zone_id
    vswitch_id = alicloud_vswitch.user2_connect_vsw.id
  }
}

resource "alicloud_cen_transit_router_route_table" "user2_region2_route_table" {
  provider = alicloud.user2_region2
  transit_router_id = alicloud_cen_transit_router.user2_region2_tr.transit_router_id
}

resource "alicloud_cen_transit_router_route_table_association" "user2_region2_association1" {
  provider = alicloud.user2_region2
  transit_router_route_table_id = alicloud_cen_transit_router_route_table.user2_region2_route_table.transit_router_route_table_id
  transit_router_attachment_id  = alicloud_cen_transit_router_vpc_attachment.user2_region2_vpc_att1.transit_router_attachment_id
}

resource "alicloud_cen_transit_router_route_table_association" "user2_region2_association2" {
  provider = alicloud.user2_region2
  transit_router_route_table_id = alicloud_cen_transit_router_route_table.user2_region2_route_table.transit_router_route_table_id
  transit_router_attachment_id  = alicloud_cen_transit_router_vpc_attachment.user2_region2_vpc_att2.transit_router_attachment_id
}

resource "alicloud_cen_transit_router_route_table_propagation" "user2_region2_propagation1" {
  provider = alicloud.user2_region2
  transit_router_route_table_id = alicloud_cen_transit_router_route_table.user2_region2_route_table.transit_router_route_table_id
  transit_router_attachment_id  = alicloud_cen_transit_router_vpc_attachment.user2_region2_vpc_att1.transit_router_attachment_id
}

resource "alicloud_cen_transit_router_route_table_propagation" "user2_region2_propagation2" {
  provider = alicloud.user2_region2
  transit_router_route_table_id = alicloud_cen_transit_router_route_table.user2_region2_route_table.transit_router_route_table_id
  transit_router_attachment_id  = alicloud_cen_transit_router_vpc_attachment.user2_region2_vpc_att2.transit_router_attachment_id
}


resource "alicloud_route_entry" "user2_region2_route_entry" {
  provider = alicloud.user2_region2
  for_each = toset([var.user1_region1_vpc_cidr_block, var.user1_region2_vpc_cidr_block,
  var.user2_region1_vpc_cidr_block, var.user2_region2_vpc_cidr_block, var.user2_connect_vpc_cidr_block])
  route_table_id        = alicloud_vpc.user2_region2_vpc.route_table_id
  destination_cidrblock = each.key
  nexthop_type          = "Attachment"
  nexthop_id            = alicloud_cen_transit_router_vpc_attachment.user2_region2_vpc_att1.transit_router_attachment_id
}


resource "alicloud_route_entry" "user2_region2_connect_route_entry1" {
  provider = alicloud.user2_region2
  for_each = toset([var.user2_region1_vpc_cidr_block, var.user2_region2_vpc_cidr_block, var.user2_connect_vpc_cidr_block])
  route_table_id        = alicloud_vpc.user2_connect_vpc.route_table_id
  destination_cidrblock = each.key
  nexthop_type          = "Attachment"
  nexthop_id            = alicloud_cen_transit_router_vpc_attachment.user2_region2_vpc_att2.transit_router_attachment_id
}

resource "alicloud_route_entry" "user2_region2_connect_route_entry2" {
  provider = alicloud.user2_region2
  for_each = toset([var.user1_region1_vpc_cidr_block, var.user1_region2_vpc_cidr_block])
  route_table_id        = alicloud_vpc.user2_connect_vpc.route_table_id
  destination_cidrblock = each.key
  nexthop_type          = "Attachment"
  nexthop_id            = alicloud_cen_transit_router_vpc_attachment.user1_region2_vpc_att2.transit_router_attachment_id
}


resource "alicloud_cen_transit_router_peer_attachment" "user2_peer_attachment" {
  provider = alicloud.user2_region2
  cen_id                                = alicloud_cen_instance.user2_cen.id
  transit_router_id                     = alicloud_cen_transit_router.user2_region2_tr.transit_router_id
  peer_transit_router_region_id         = var.region1
  peer_transit_router_id                = alicloud_cen_transit_router.user2_region1_tr.transit_router_id
  auto_publish_route_enabled  = true
}

resource "alicloud_cen_transit_router_route_table_association" "user2_region2_association3" {
  provider = alicloud.user2_region2
  transit_router_route_table_id = alicloud_cen_transit_router_route_table.user2_region2_route_table.transit_router_route_table_id
  transit_router_attachment_id  = alicloud_cen_transit_router_peer_attachment.user2_peer_attachment.transit_router_attachment_id
}

resource "alicloud_cen_transit_router_route_table_propagation" "user2_region2_propagation3" {
  provider = alicloud.user2_region2
  transit_router_route_table_id = alicloud_cen_transit_router_route_table.user2_region2_route_table.transit_router_route_table_id
  transit_router_attachment_id  = alicloud_cen_transit_router_peer_attachment.user2_peer_attachment.transit_router_attachment_id
}

resource "alicloud_cen_transit_router_route_table_association" "user2_region2_association4" {
  provider = alicloud.user2_region2
  transit_router_route_table_id = alicloud_cen_transit_router_route_table.user2_region1_route_table.transit_router_route_table_id
  transit_router_attachment_id  = alicloud_cen_transit_router_peer_attachment.user2_peer_attachment.transit_router_attachment_id
}

resource "alicloud_cen_transit_router_route_table_propagation" "user2_region2_propagation4" {
  provider = alicloud.user2_region2
  transit_router_route_table_id = alicloud_cen_transit_router_route_table.user2_region1_route_table.transit_router_route_table_id
  transit_router_attachment_id  = alicloud_cen_transit_router_peer_attachment.user2_peer_attachment.transit_router_attachment_id
}

resource "alicloud_cen_transit_router_route_entry" "user2_route_entry" {
  provider = alicloud.user2_region2
  transit_router_route_table_id                     = alicloud_cen_transit_router_route_table.user2_region2_route_table.transit_router_route_table_id
  for_each = toset([var.user1_region1_vpc_cidr_block, var.user1_region2_vpc_cidr_block])
  transit_router_route_entry_destination_cidr_block = each.key
  transit_router_route_entry_next_hop_type          = "Attachment"
  transit_router_route_entry_name                   = "link_route_entry"
  transit_router_route_entry_next_hop_id            = alicloud_cen_transit_router_vpc_attachment.user2_region2_vpc_att2.transit_router_attachment_id
}

# main

resource "alicloud_cen_instance_grant" "grant" {
  provider = alicloud.user2_region2
  cen_id            = alicloud_cen_instance.user1_cen.id
  child_instance_id = alicloud_vpc.user2_connect_vpc.id
  cen_owner_id      = var.user1_id
}

resource "alicloud_cen_transit_router_vpc_attachment" "user1_region2_vpc_att2" {
  provider = alicloud.user1_region2
  cen_id            = alicloud_cen_instance.user1_cen.id
  transit_router_id = alicloud_cen_transit_router.user1_region2_tr.transit_router_id
  vpc_id            = alicloud_vpc.user2_connect_vpc.id
  zone_mappings {
    zone_id    = var.region2_zone_id
    vswitch_id = alicloud_vswitch.user2_connect_vsw.id
  }
  vpc_owner_id = var.user2_id
  depends_on = [alicloud_cen_instance_grant.grant]
}

resource "alicloud_cen_transit_router_route_table_association" "table_association" {
  provider = alicloud.user1_region2
  transit_router_route_table_id = alicloud_cen_transit_router_route_table.user1_region2_route_table.transit_router_route_table_id
  transit_router_attachment_id  = alicloud_cen_transit_router_vpc_attachment.user1_region2_vpc_att2.transit_router_attachment_id
}

resource "alicloud_cen_transit_router_route_entry" "user1_route_entry" {
  provider = alicloud.user1_region2
  for_each = toset([var.user2_region1_vpc_cidr_block, var.user2_region2_vpc_cidr_block, var.user2_connect_vpc_cidr_block])
  transit_router_route_table_id                     = alicloud_cen_transit_router_route_table.user1_region2_route_table.transit_router_route_table_id
  transit_router_route_entry_destination_cidr_block = each.key
  transit_router_route_entry_next_hop_type          = "Attachment"
  transit_router_route_entry_name                   = "link_route_entry"
  transit_router_route_entry_next_hop_id            = alicloud_cen_transit_router_vpc_attachment.user1_region2_vpc_att2.transit_router_attachment_id
}

resource "alicloud_cen_transit_router_route_table_propagation" "table_propagation" {
  provider = alicloud.user1_region2
  transit_router_route_table_id = alicloud_cen_transit_router_route_table.user1_region2_route_table.transit_router_route_table_id
  transit_router_attachment_id  = alicloud_cen_transit_router_vpc_attachment.user1_region2_vpc_att2.transit_router_attachment_id
}

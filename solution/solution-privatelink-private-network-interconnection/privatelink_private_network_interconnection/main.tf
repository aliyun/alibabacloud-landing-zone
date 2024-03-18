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
  alias = "user1_region1"
  region = var.region1
  assume_role {
    role_arn = local.user1_is_admin ? null : format("acs:ram::%s:role/%s", local.user1_id, local.role_name)
    session_name = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

provider "alicloud" {
  alias = "user1_region2"
  region = var.region2
  assume_role {
    role_arn = local.user1_is_admin ? null : format("acs:ram::%s:role/%s", local.user1_id, local.role_name)
    session_name = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

provider "alicloud" {
  alias = "user2_region1"
  region = var.region1
  assume_role {
    role_arn = local.user2_is_admin ? null : format("acs:ram::%s:role/%s", local.user2_id, local.role_name)
    session_name = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}


module "user1_region1_vpc" {
  count = 1
  source = "./vpc"
  providers = {alicloud: alicloud.user1_region1}
  vpc_cidr = var.vpc2_cidr
}

module "user1_region2_vpc" {
  count = 1
  source = "./vpc"
  providers = {alicloud: alicloud.user1_region2}
  vpc_cidr = var.vpc3_cidr
}

module "user2_region1_vpc" {
  count = 1
  source = "./vpc"
  providers = {alicloud: alicloud.user2_region1}
  vpc_cidr = var.vpc1_cidr
}

resource "alicloud_vswitch" "vsw1" {
  provider = alicloud.user2_region1
  vswitch_name = "vsw1"
  vpc_id = module.user2_region1_vpc[0].vpc_id
  cidr_block = var.vsw1_cidr
  zone_id = var.zone1_id
}

resource "alicloud_vswitch" "vsw2" {
  provider = alicloud.user1_region1
  vswitch_name = "vsw2"
  vpc_id = module.user1_region1_vpc[0].vpc_id
  cidr_block = var.vsw2_cidr
  zone_id = var.zone2_id
}

resource "alicloud_vswitch" "vsw4" {
  provider = alicloud.user1_region2
  vswitch_name = "vsw4"
  vpc_id = module.user1_region2_vpc[0].vpc_id
  cidr_block = var.vsw4_cidr
  zone_id = var.zone4_id
}

module "user2_region1_sg" {
  source = "./sg"
  providers = {alicloud: alicloud.user2_region1}
  vpc_id = module.user2_region1_vpc[0].vpc_id
}

module "user1_region1_sg" {
  source = "./sg"
  providers = {alicloud: alicloud.user1_region1}
  vpc_id = module.user1_region1_vpc[0].vpc_id
}

module "user1_region2_sg" {
  source = "./sg"
  providers = {alicloud: alicloud.user1_region2}
  vpc_id = module.user1_region2_vpc[0].vpc_id
}


module "user1_region1_ecs" {
  count = 1
  source = "./ecs"
  providers = {alicloud: alicloud.user1_region1}
  create_ecs = var.create_ecs
  vpc_id = module.user1_region1_vpc[0].vpc_id
  vsw_id = alicloud_vswitch.vsw2.id
  zone_id = var.zone2_id
  sg_id = module.user1_region1_sg.sg_id
  instance_type =  var.instance_type2
  system_disk_category = var.system_disk_category2
  ecs_password = var.user1_ecs_password
  instance_name = format("test_ecs%s",count.index+1)
}

module "user1_region2_ecs" {
  count = 1
  source = "./ecs"
  providers = {alicloud: alicloud.user1_region2}
  create_ecs = var.create_ecs
  vpc_id = module.user1_region2_vpc[0].vpc_id
  vsw_id = alicloud_vswitch.vsw4.id
  zone_id = var.zone4_id
  sg_id = module.user1_region2_sg.sg_id
  instance_type =  var.instance_type4
  system_disk_category = var.system_disk_category4
  ecs_password = var.user1_ecs_password
  instance_name = format("test_ecs%s",count.index+1)
}

module "user2_region1_ecs" {
  count = 1
  source = "./ecs"
  providers = {alicloud: alicloud.user2_region1}
  create_ecs = var.create_ecs
  vpc_id = module.user2_region1_vpc[0].vpc_id
  vsw_id = alicloud_vswitch.vsw1.id
  zone_id = var.zone1_id
  sg_id = module.user2_region1_sg.sg_id
  instance_type = var.instance_type1
  system_disk_category = var.system_disk_category1
  ecs_password = var.user2_ecs_password
  instance_name = format("test_ecs%s",count.index+3)
}


locals {
  vpc_ids = concat(module.user1_region1_vpc.*.vpc_id, module.user1_region2_vpc.*.vpc_id, module.user2_region1_vpc.*.vpc_id)
}


resource "alicloud_slb_load_balancer" "load_balancer" {
  provider = alicloud.user2_region1
  load_balancer_name = "privatelink-service"
  load_balancer_spec = "slb.s1.small"
  address_type       = "intranet"
  vswitch_id    = alicloud_vswitch.vsw1.id
  instance_charge_type = "PayBySpec"
}

resource "alicloud_slb_listener" "listener" {
  provider = alicloud.user2_region1
  load_balancer_id          = alicloud_slb_load_balancer.load_balancer.id
  backend_port              = 8080
  frontend_port             = 80
  protocol                  = "tcp"
  health_check              = "on"
  health_check_connect_port = 80
  bandwidth                 = -1
  healthy_threshold         = 3
  unhealthy_threshold       = 3
  health_check_timeout      = 5
  health_check_interval     = 2
  health_check_http_code    = "http_2xx,http_3xx,http_4xx,http_5xx"
}

resource "alicloud_slb_backend_server" "backend_server" {
  count = var.create_ecs? 1:0
  provider = alicloud.user2_region1
  load_balancer_id = alicloud_slb_load_balancer.load_balancer.id
  backend_servers {
    server_id = module.user2_region1_ecs[0].ecs_instance_id[0]
    weight    = 100
  }
}


resource "alicloud_privatelink_vpc_endpoint_service" "privatelink_vpc_endpoint_service" {
  provider = alicloud.user2_region1
  auto_accept_connection = false
}

resource "alicloud_privatelink_vpc_endpoint_service_resource" "privatelink_vpc_endpoint_service_resource" {
  provider = alicloud.user2_region1
  resource_id   = alicloud_slb_load_balancer.load_balancer.id
  resource_type = "slb"
  service_id    = alicloud_privatelink_vpc_endpoint_service.privatelink_vpc_endpoint_service.id
}

resource "alicloud_privatelink_vpc_endpoint_service_user" "privatelink_vpc_endpoint_service_user" {
  provider = alicloud.user2_region1
  service_id = alicloud_privatelink_vpc_endpoint_service.privatelink_vpc_endpoint_service.id
  user_id    = local.user1_id
}

resource "alicloud_privatelink_vpc_endpoint" "privatelink_vpc_endpoint" {
  provider = alicloud.user1_region1
  service_id        = alicloud_privatelink_vpc_endpoint_service.privatelink_vpc_endpoint_service.id
  security_group_ids = [module.user1_region1_sg.sg_id]
  vpc_id            = module.user1_region1_vpc[0].vpc_id
}

resource "alicloud_privatelink_vpc_endpoint_zone" "zone1" {
  provider = alicloud.user1_region1
  endpoint_id = alicloud_privatelink_vpc_endpoint.privatelink_vpc_endpoint.id
  vswitch_id  = alicloud_vswitch.vsw2.id
  zone_id     = var.zone2_id
}

resource "alicloud_privatelink_vpc_endpoint_connection" "privatelink_vpc_endpoint_connection" {
  provider = alicloud.user2_region1
  endpoint_id = alicloud_privatelink_vpc_endpoint.privatelink_vpc_endpoint.id
  service_id  = alicloud_privatelink_vpc_endpoint_service.privatelink_vpc_endpoint_service.id
  bandwidth   = "1024"
}


resource "alicloud_cen_instance" "cen" {
  provider = alicloud.user1_region1
  cen_instance_name = "云上企业网络"
}


resource "alicloud_cen_bandwidth_package" "cen_bandwidth_package" {
  provider = alicloud.user1_region1
  bandwidth                  = 5
  charge_type                = "PostPaid"
  geographic_region_ids = [
    "China",
    "China"
  ]
}

resource "alicloud_cen_bandwidth_package_attachment" "cen_bandwidth_package_attachment" {
  provider = alicloud.user1_region1
  instance_id          = alicloud_cen_instance.cen.id
  bandwidth_package_id = alicloud_cen_bandwidth_package.cen_bandwidth_package.id
}


resource "alicloud_cen_instance_attachment" "cen_instance_attachment1" {
  provider = alicloud.user1_region1
  instance_id              = alicloud_cen_instance.cen.id
  child_instance_id        = module.user1_region1_vpc[0].vpc_id
  child_instance_type      = "VPC"
  child_instance_region_id = var.region1
  depends_on = [alicloud_privatelink_vpc_endpoint_zone.zone1]
}

resource "alicloud_cen_instance_attachment" "cen_instance_attachment2" {
  provider = alicloud.user1_region1
  instance_id              = alicloud_cen_instance.cen.id
  child_instance_id        = module.user1_region2_vpc[0].vpc_id
  child_instance_type      = "VPC"
  child_instance_region_id = var.region2
  depends_on = [alicloud_cen_instance_attachment.cen_instance_attachment1]
}
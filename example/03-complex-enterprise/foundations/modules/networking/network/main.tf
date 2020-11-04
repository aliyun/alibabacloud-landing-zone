# 创建企业专有网络 Shared Service VPC
resource "alicloud_vpc" "vpc_shared_service" {
  name       = var.network_settings.vpc_shared_services.vpc_name
  cidr_block = var.network_settings.vpc_shared_services.cidr_block
}
# 创建 Shared Service VPC 内的交换机
resource "alicloud_vswitch" "shared_service_vswitches" {
  for_each          = {
    for vsw in var.network_settings.vpc_shared_services.vswitches : "${vsw.vswitch_name}" => vsw
  }
  name              = each.value.vswitch_name
  vpc_id            = alicloud_vpc.vpc_shared_service.id
  cidr_block        = each.value.cidr_block
  availability_zone = each.value.zone
}

# 创建企业专有网络 DMZ VPC
resource "alicloud_vpc" "vpc_dmz" {
  name       = var.network_settings.vpc_dmz.vpc_name
  cidr_block = var.network_settings.vpc_dmz.cidr_block
}
# 创建 DMZ VPC 内的交换机
resource "alicloud_vswitch" "dmz_vswitches" {
  for_each          = {
    for vsw in var.network_settings.vpc_dmz.vswitches : "${vsw.vswitch_name}" => vsw
  }
  name              = each.value.vswitch_name
  vpc_id            = alicloud_vpc.vpc_dmz.id
  cidr_block        = each.value.cidr_block
  availability_zone = each.value.zone
}

# 创建 Production VPC
resource "alicloud_vpc" "vpc_production" {
  name       = var.network_settings.vpc_production.vpc_name
  cidr_block = var.network_settings.vpc_production.cidr_block
}

# 创建 Non-Production VPC
resource "alicloud_vpc" "vpc_non_production" {
  name       = var.network_settings.vpc_non_production.vpc_name
  cidr_block = var.network_settings.vpc_non_production.cidr_block
}

# 创建NAT,eip,共享带宽
module "nat" {
  source = "./nat"
  count = var.network_settings.vpc_dmz.natgateway_enabled ? 1 : 0
  
  vpc_id = alicloud_vpc.vpc_dmz.id
  eip_bandwidth = var.network_settings.vpc_dmz.eip_bandwidth
  eip_internet_charge_type = var.network_settings.vpc_dmz.eip_internet_charge_type
  common_bandwidth_package_enabled = var.network_settings.vpc_dmz.common_bandwidth_package_enabled
  common_bandwidth_package_bandwidth = var.network_settings.vpc_dmz.common_bandwidth_package_bandwidth
  common_bandwidth_package_internet_charge_type = var.network_settings.vpc_dmz.common_bandwidth_package_internet_charge_type
}

# 创建 CEN
resource "alicloud_cen_instance" "cen" {
  cen_instance_name = "cen"
}

locals {
  vpc_ids = [
    alicloud_vpc.vpc_shared_service.id,
    alicloud_vpc.vpc_dmz.id,
    alicloud_vpc.vpc_production.id,
    alicloud_vpc.vpc_non_production.id
  ]
}

data "alicloud_vpcs" "vpcs_ds" {
  ids = local.vpc_ids
}

resource "alicloud_cen_instance_attachment" "cen_shared_service_vpc_attachment" {
  instance_id              = alicloud_cen_instance.cen.id
  child_instance_id        = alicloud_vpc.vpc_shared_service.id
  child_instance_region_id = var.region
  child_instance_type      = "VPC"
}

resource "alicloud_cen_instance_attachment" "cen_dmz_vpc_attachment" {
  instance_id              = alicloud_cen_instance.cen.id
  child_instance_id        = alicloud_vpc.vpc_dmz.id
  child_instance_region_id = var.region
  child_instance_type      = "VPC"
}

resource "alicloud_cen_instance_attachment" "cen_production_vpc_attachment" {
  instance_id              = alicloud_cen_instance.cen.id
  child_instance_id        = alicloud_vpc.vpc_production.id
  child_instance_region_id = var.region
  child_instance_type      = "VPC"
}

resource "alicloud_cen_instance_attachment" "cen_non_production_vpc_attachment" {
  instance_id              = alicloud_cen_instance.cen.id
  child_instance_id        = alicloud_vpc.vpc_non_production.id
  child_instance_region_id = var.region
  child_instance_type      = "VPC"
}

# 发布nat自定义路由到云企业网
data "alicloud_route_tables" "vpc_route_table_ds" {
  vpc_id  = alicloud_vpc.vpc_dmz.id
}
resource "alicloud_cen_route_entry" "cen_nat_route_entry" {
  count = var.network_settings.vpc_dmz.natgateway_enabled ? 1 : 0
  instance_id    = alicloud_cen_instance.cen.id
  route_table_id = data.alicloud_route_tables.vpc_route_table_ds.ids[0]
  cidr_block     = "0.0.0.0/0"
}


module "bastionhost" {
  source = "./bastion"

  count = var.network_settings.bastion_host_enabled ? 1 : 0

  vswitch_id = alicloud_vswitch.shared_service_vswitches.0.id
  # TODO security group id
  security_group_ids = []
}
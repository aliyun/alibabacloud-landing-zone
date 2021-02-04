# 创建VPC 内的交换机
resource "alicloud_vswitch" "vswitches_business" {
  name              = var.vswitch_name
  vpc_id            = var.vpc_id
  cidr_block        = var.cidr_block
  availability_zone = var.zone
}

# 创建交换机的nat eip
module "nat" {
  source = "../nat"
  count  = var.nat.natgateway_enabled ? 1 : 0
  vswitch_id = alicloud_vswitch.vswitches_business.id
  nat    = var.nat
  cen_id = var.cen_id
  vpc_id = var.vpc_id
}


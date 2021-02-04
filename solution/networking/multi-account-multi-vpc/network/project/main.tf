module "network_vswitch_nat" {
  source       = "./vswitch"
  for_each     = var.vswitches
  vpc_id       = var.vpc_id
  vswitch_name = each.key
  cidr_block   = each.value.cidr_block
  zone         = each.value.zone
  nat          = each.value.nat
  cen_id       = var.cen_id
}

# 创建nacl,隔离各项目交换机
module "vpc_nacl" {
  source = "./nacl"
  count  = var.network_acl_enabled ? 1 : 0
  vpc_id = var.vpc_id
  network_acl_name = "${var.project_name}_acl"
  vswitches = {
    for o in keys(module.network_vswitch_nat) : o => module.network_vswitch_nat[o].vswitch_app
  }
}
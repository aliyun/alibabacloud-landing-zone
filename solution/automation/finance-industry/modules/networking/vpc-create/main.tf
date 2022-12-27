// If there is not specifying vpc_id, the module will launch a new vpc
resource "alicloud_vpc" "vpc" {
  count       = var.vpc_id == "" ? 1 : 0
  vpc_name    = var.vpc_name
  cidr_block  = var.vpc_cidr
  description = var.vpc_desc
  tags        = var.vpc_tags
}

// According to the vswitch cidr blocks to launch several vswitches
resource "alicloud_vswitch" "vswitches" {
  for_each     = {for vsw in var.vswitch_configuration : vsw.vswitch_name => vsw}
  vpc_id       = var.vpc_id == "" ? concat(alicloud_vpc.vpc.*.id, [""])[0] : var.vpc_id
  cidr_block   = each.value.vswitch_cidr
  zone_id      = each.value.zone_id
  vswitch_name = each.value.vswitch_name
  description  = each.value.vswitch_desc
  tags         = each.value.vswitch_tags
}


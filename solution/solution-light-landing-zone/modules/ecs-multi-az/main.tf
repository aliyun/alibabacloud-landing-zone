resource "alicloud_security_group" "group" {
  name        = var.security_group_name
  description = var.security_group_desc
  vpc_id      = var.vpc_id
}

resource "alicloud_security_group_rule" "security_group_rule" {
  count             = length(var.security_group_rule)
  security_group_id = alicloud_security_group.group.id

  type        = var.security_group_rule[count.index].type
  ip_protocol = var.security_group_rule[count.index].ip_protocol
  nic_type    = var.security_group_rule[count.index].nic_type
  policy      = var.security_group_rule[count.index].policy
  port_range  = var.security_group_rule[count.index].port_range
  priority    = var.security_group_rule[count.index].priority
  cidr_ip     = var.security_group_rule[count.index].cidr_ip
}

resource "alicloud_instance" "instances" {
  count = length(var.ecs_instance_deploy_config)

  resource_group_id = var.resource_group_id
  security_groups   = alicloud_security_group.group.*.id

  vswitch_id    = var.ecs_instance_deploy_config[count.index].vswitch_id
  instance_name = var.ecs_instance_deploy_config[count.index].instance_name
  description   = var.ecs_instance_deploy_config[count.index].description
  host_name     = var.ecs_instance_deploy_config[count.index].host_name
  password      = var.ecs_instance_password

  instance_type              = var.ecs_instance_spec.instance_type
  system_disk_category       = var.ecs_instance_spec.system_disk_category
  image_id                   = var.ecs_instance_spec.image_id
  instance_charge_type       = var.ecs_instance_spec.instance_charge_type
  period_unit                = var.ecs_instance_spec.period_unit
  period                     = var.ecs_instance_spec.period
  internet_max_bandwidth_out = var.ecs_instance_spec.internet_max_bandwidth_out
  tags                       = var.ecs_instance_spec.tags
  volume_tags                = var.ecs_instance_spec.volume_tags
}

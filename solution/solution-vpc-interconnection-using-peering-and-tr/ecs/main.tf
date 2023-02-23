variable "create_ecs" {}
variable "vpc_id" {}
variable "vsw_id" {}
variable "zone_id" {}
variable "instance_type" {}
variable "system_disk_category" {}
variable "ecs_password" {}
variable "instance_name" {}

resource "alicloud_security_group" "group" {
  count  = var.create_ecs? 1:0
  vpc_id = var.vpc_id
}

resource "alicloud_security_group_rule" "rule" {
  count             = var.create_ecs? 1:0
  type              = "ingress"
  ip_protocol       = "all"
  nic_type          = "intranet"
  policy            = "accept"
  port_range        = "1/65535"
  priority          = 1
  security_group_id = alicloud_security_group.group[0].id
  cidr_ip           = "0.0.0.0/0"
}

resource "alicloud_instance" "ecs" {
  count = var.create_ecs? 1:0
  availability_zone = var.zone_id
  security_groups   = alicloud_security_group.group[0].*.id
  instance_type              = var.instance_type
  system_disk_category       = var.system_disk_category
  image_id                   = "centos_7_9_x64_20G_alibase_20220824.vhd"
  instance_name              = var.instance_name
  vswitch_id                 = var.vsw_id
  password                   = var.ecs_password
}


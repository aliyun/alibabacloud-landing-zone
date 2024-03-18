variable "create_ecs" {}
variable "vpc_id" {}
variable "vsw_id" {}
variable "zone_id" {}
variable "sg_id" {}
variable "instance_type" {}
variable "system_disk_category" {}
variable "ecs_password" {}
variable "instance_name" {}

terraform {
  required_providers {
    alicloud = {
      source  = "hashicorp/alicloud"
      version = "~> 1.1"
    }
  }
}

resource "alicloud_instance" "ecs" {
  count = var.create_ecs? 1:0
  availability_zone = var.zone_id
  security_groups   = [var.sg_id]
  instance_type              = var.instance_type
  system_disk_category       = var.system_disk_category
  image_id                   = "centos_7_9_x64_20G_alibase_20220824.vhd"
  instance_name              = var.instance_name
  vswitch_id                 = var.vsw_id
  password                   = var.ecs_password
}

output "ecs_instance_id" {
  value = alicloud_instance.ecs.*.id
}


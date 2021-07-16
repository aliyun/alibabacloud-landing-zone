provider "alicloud" {
}

data "alicloud_zones" "default" {
  available_disk_category     = "cloud_efficiency"
  available_resource_creation = "VSwitch"
}

# Create a new VPC
resource "alicloud_vpc" "vpc" {
  vpc_name   = "golden_image_vpc"
  cidr_block = "172.16.0.0/16"
}

# Create a new VSwitch
resource "alicloud_vswitch" "vswitch" {
  vpc_id            = alicloud_vpc.vpc.id
  cidr_block        = "172.16.0.0/24"
  zone_id           = data.alicloud_zones.default.zones[0].id
  vswitch_name      = "golden_image_vswitch"
}

# Create a security group for ECS
resource "alicloud_security_group" "group" {
  name        = "golden_image_builder"
  description = "security groups for ecs which is used for building golden image"
  vpc_id      = alicloud_vpc.vpc.id
}

# Create a new ECS instance for building Golden Image
resource "alicloud_instance" "ecs_golden_image" {
  availability_zone = data.alicloud_zones.default.zones[0].id
  security_groups   = alicloud_security_group.group.*.id

  instance_type              = "ecs.n4.large"
  system_disk_category       = "cloud_efficiency"
  system_disk_name           = "golden_image"
  system_disk_description    = "golden_image"
  image_id                   = "ubuntu_18_04_64_20G_alibase_20190624.vhd"
  instance_name              = "golden_image_builder"
  instance_charge_type       = "PrePaid"
  period                     = 1
  period_unit                = "Month"
  vswitch_id                 = alicloud_vswitch.vswitch.id
  internet_max_bandwidth_out = 0
  user_data                  = base64encode("sleep 100 && echo 1")
}

# resource "time_sleep" "wait_for_user_data" {
#   depends_on = [alicloud_instance.ecs_golden_image]

#   create_duration = "100s"
# }

# resource "alicloud_ecs_command" "shutdown" {
#     name              = "shutdown_ecs"
#     command_content   = "Stop-Computer -ComputerName localhost"
#     description       = "shutdown"
#     type              = "RunPowerShellScript"
# }

output "ecs_instance_id" {
  value = alicloud_instance.ecs_golden_image.id
}
# 创建 VSwitch
resource "alicloud_vswitch" "vswitch_app" {
  name              = var.vswitch_name
  vpc_id            = var.vpc_id
  cidr_block        = var.cidr_block
  availability_zone = var.zone
}

# 将创建的 VSwitch 共享给改成员账号
resource "alicloud_resource_manager_shared_resource" "shared_vswitches" {
  resource_id       = alicloud_vswitch.vswitch_app.id
  resource_share_id = var.resource_share_id
  resource_type     = "VSwitch"
}
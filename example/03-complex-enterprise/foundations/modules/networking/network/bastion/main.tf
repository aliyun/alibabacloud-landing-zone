
resource "alicloud_yundun_bastionhost_instance" "default" {
  description        = var.description
  period             = var.period
  vswitch_id         = var.vswitch_id
  security_group_ids = var.security_group_ids
  license_code       = "bhah_ent_50_asset"
}
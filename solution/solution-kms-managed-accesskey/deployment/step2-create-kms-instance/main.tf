provider "alicloud" {
  region = var.region
}

data "alicloud_kms_service" "open_service" {
  enable = "On"
}

resource "alicloud_kms_instance" "instance" {
  product_version = var.instance_type == "hardware" ? "5" : "3"
  spec            = var.performance
  key_num         = var.key_num
  secret_num      = var.secret_num
  vpc_num         = var.access_num
  log             = var.log ? "1" : "0"
  log_storage     = var.log ? var.log_storage : null
  period          = var.purchase_period
  renew_status    = var.auto_renew ? "AutoRenewal" : null
  renew_period    = var.auto_renew ? var.purchase_period : null
  vpc_id          = var.vpc_id
  zone_ids        = var.zone_ids
  vswitch_ids     = [var.vswitch_id]

  dynamic "bind_vpcs" {
    for_each = var.bind_vpcs
    content {
      region_id    = var.region
      vswitch_id   = bind_vpcs.value["vswitch_id"]
      vpc_id       = bind_vpcs.value["vpc_id"]
      vpc_owner_id = bind_vpcs.value["vpc_owner_id"]
    }
  }
}

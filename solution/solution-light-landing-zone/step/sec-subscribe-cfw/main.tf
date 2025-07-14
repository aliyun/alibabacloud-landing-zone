locals {
  account_json              = fileexists("../var/account.json") ? jsondecode(file("../var/account.json")) : {}
  security_account_id       = var.security_account_id == "" ? local.account_json["security_account_id"] : var.security_account_id
}

provider "alicloud" {
    alias = "security_account"
    assume_role {
      role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.security_account_id)
      session_name       = "AccountLandingZoneSetup"
      session_expiration = 999
    }
}

resource "alicloud_cloud_firewall_instance" "cfw_instance" {
  provider = alicloud.security_account
  payment_type    = var.cfw_instance_spec.payment_type
  spec            = var.cfw_instance_spec.spec
  ip_number       = var.cfw_instance_spec.ip_number
  band_width      = var.cfw_instance_spec.band_width
  cfw_log         = var.cfw_instance_spec.cfw_log
  cfw_log_storage = var.cfw_instance_spec.cfw_log_storage
  cfw_service     = var.cfw_instance_spec.cfw_service
  fw_vpc_number   = var.cfw_instance_spec.fw_vpc_number
  period          = var.cfw_instance_spec.period
}

resource "alicloud_cloud_firewall_control_policy" "cfw_cp" {
  provider = alicloud.security_account
  count            = length(var.cfw_control_policy)
  application_name = var.cfw_control_policy[count.index].application_name
  acl_action       = var.cfw_control_policy[count.index].acl_action
  description      = var.cfw_control_policy[count.index].description
  destination_type = var.cfw_control_policy[count.index].destination_type
  destination      = var.cfw_control_policy[count.index].destination
  dest_port        = var.cfw_control_policy[count.index].dest_port
  dest_port_type   = var.cfw_control_policy[count.index].dest_port_type
  direction        = var.cfw_control_policy[count.index].direction
  proto            = var.cfw_control_policy[count.index].proto
  source           = var.cfw_control_policy[count.index].source
  source_type      = var.cfw_control_policy[count.index].source_type
}

resource "alicloud_cloud_firewall_control_policy_order" "cfw_cp_order" {
  provider = alicloud.security_account
  count     = length(alicloud_cloud_firewall_control_policy.cfw_cp)
  acl_uuid  = alicloud_cloud_firewall_control_policy.cfw_cp[count.index].acl_uuid
  direction = alicloud_cloud_firewall_control_policy.cfw_cp[count.index].direction
  order     = var.cfw_control_policy[count.index].order
}




locals {
  account_json              = fileexists("../var/account.json") ? jsondecode(file("../var/account.json")) : {}
  shared_service_account_id = var.shared_service_account_id == "" ? local.account_json["shared_service_account_id"] : var.shared_service_account_id

  shared_service_account_vpc_config = var.shared_service_account_vpc_config
  all_vpc_cidr                             = var.all_vpc_cidr

  dmz_egress_eip_name         = var.dmz_egress_eip_name
  dmz_egress_nat_gateway_name = var.dmz_egress_nat_gateway_name

  vpc_json                          = fileexists("../var/vpc.json") ? jsondecode(file("../var/vpc.json")) : {}
  shared_service_account_vpc_id     = var.shared_service_account_vpc_id == "" ? local.vpc_json["shared_service_account"]["vpc_id"] : var.shared_service_account_vpc_id
  shared_service_account_vswitch_id = var.shared_service_account_vswitch_id == "" ? local.vpc_json["shared_service_account"]["vsw1_id"] : var.shared_service_account_vswitch_id
}


provider "alicloud" {
  alias  = "shared_service_account"
  region = local.shared_service_account_vpc_config["region"]
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.shared_service_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

module "shared_service_account_dmz" {
  source                 = "../../modules/vpc-dmz"
  providers              = {
    alicloud = alicloud.shared_service_account
  }
  vpc_id                 = local.shared_service_account_vpc_id
  all_vpc_cidr           = local.all_vpc_cidr
  nat_gateway_name       = local.dmz_egress_nat_gateway_name
  nat_gateway_vswitch_id = local.shared_service_account_vswitch_id
  eip_address_name       = local.dmz_egress_eip_name
}


# Save dmz information
resource "local_file" "account_json" {
  content    = templatefile("../var/dmz.json.tmpl", {
    egress_eip_id         = module.shared_service_account_dmz.egress_eip_id
    egress_eip_ip_address = module.shared_service_account_dmz.egress_eip_ip_address
    egress_nat_gateway_id = module.shared_service_account_dmz.egress_nat_gateway_id
    egress_snat_entry_id  = module.shared_service_account_dmz.egress_snat_entry_id
  })
  filename   = "../var/dmz.json"
  depends_on = [
    module.shared_service_account_dmz
  ]
}

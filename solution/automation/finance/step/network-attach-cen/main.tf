locals {
  account_json              = fileexists("../var/account.json") ? jsondecode(file("../var/account.json")) : {}
  shared_service_account_id = var.shared_service_account_id == "" ? local.account_json["shared_service_account_id"] : var.shared_service_account_id
  ops_account_id            = var.ops_account_id == "" ? local.account_json["ops_account_id"] : var.ops_account_id
  dev_account_id            = var.dev_account_id == "" ? local.account_json["dev_account_id"] : var.dev_account_id
  prod_account_id           = var.prod_account_id == "" ? local.account_json["prod_account_id"] : var.prod_account_id

  shared_service_account_vpc_config = var.shared_service_account_vpc_config
  dev_account_vpc_config            = var.dev_account_vpc_config
  prod_account_vpc_config           = var.prod_account_vpc_config
  ops_account_vpc_config            = var.ops_account_vpc_config
  all_vpc_cidr                             = var.all_vpc_cidr

  vpc_json                      = fileexists("../var/vpc.json") ? jsondecode(file("../var/vpc.json")) : {}
  shared_service_account_vpc_id = var.shared_service_account_vpc_id == "" ? local.vpc_json["shared_service_account"]["vpc_id"] : var.shared_service_account_vpc_id
  dev_account_vpc_id            = var.dev_account_vpc_id == "" ? local.vpc_json["dev_account"]["vpc_id"] : var.dev_account_vpc_id
  prod_account_vpc_id           = var.prod_account_vpc_id == "" ? local.vpc_json["prod_account"]["vpc_id"] : var.prod_account_vpc_id
  ops_account_vpc_id            = var.ops_account_vpc_id == "" ? local.vpc_json["ops_account"]["vpc_id"] : var.ops_account_vpc_id
}


provider "alicloud" {
  alias  = "shared_service_account"
  region = local.shared_service_account_vpc_config.region
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.shared_service_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

resource "alicloud_cen_instance" "cen" {
  provider          = alicloud.shared_service_account
  cen_instance_name = var.cen_instance_name
  description       = var.cen_instance_desc
  tags              = var.cen_instance_tags
}

resource "alicloud_cen_transit_router" "cen_tr" {
  provider            = alicloud.shared_service_account
  cen_id              = alicloud_cen_instance.cen.id
  transit_router_name = "tr-${local.shared_service_account_vpc_config.region}"
}

locals {
  cen_instance_id       = alicloud_cen_instance.cen.id
  cen_transit_router_id = alicloud_cen_transit_router.cen_tr.transit_router_id
}

module "shared_service_account_cen_attach" {
  source    = "../../modules/cen-vpc-attach"
  providers = {
    alicloud.shared_service_account = alicloud.shared_service_account
    alicloud.vpc_account            = alicloud.shared_service_account
  }

  cen_tr_account_id     = local.shared_service_account_id
  vpc_account_id        = local.shared_service_account_id
  cen_instance_id       = local.cen_instance_id
  cen_transit_router_id = local.cen_transit_router_id
  vpc_id                = local.shared_service_account_vpc_id
  all_vpc_cidr          = local.all_vpc_cidr

  primary_vswitch = {
    vswitch_id = local.vpc_json.shared_service_account.vsw1_id,
    zone_id    = local.shared_service_account_vpc_config.vswitch.0.zone_id
  }

  secondary_vswitch = {
    vswitch_id = local.vpc_json.shared_service_account.vsw2_id,
    zone_id    = local.shared_service_account_vpc_config.vswitch.1.zone_id
  }

  route_table_association_enabled = true
  route_table_propagation_enabled = true
}


provider "alicloud" {
  alias  = "dev_account"
  region = local.dev_account_vpc_config.region
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.dev_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

module "dev_account_cen_attach" {
  source    = "../../modules/cen-vpc-attach"
  providers = {
    alicloud.shared_service_account = alicloud.shared_service_account
    alicloud.vpc_account            = alicloud.dev_account
  }

  cen_tr_account_id     = local.shared_service_account_id
  vpc_account_id        = local.dev_account_id
  cen_instance_id       = local.cen_instance_id
  cen_transit_router_id = local.cen_transit_router_id
  vpc_id                = local.dev_account_vpc_id
  all_vpc_cidr          = local.all_vpc_cidr

  primary_vswitch = {
    vswitch_id = local.vpc_json.dev_account.vsw1_id,
    zone_id    = local.dev_account_vpc_config.vswitch.0.zone_id
  }

  secondary_vswitch = {
    vswitch_id = local.vpc_json.dev_account.vsw2_id,
    zone_id    = local.dev_account_vpc_config.vswitch.1.zone_id
  }

  route_table_association_enabled = true
  route_table_propagation_enabled = true

  depends_on = [module.shared_service_account_cen_attach]
}

provider "alicloud" {
  alias  = "prod_account"
  region = local.prod_account_vpc_config.region
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.prod_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

module "prod_account_cen_attach" {
  source    = "../../modules/cen-vpc-attach"
  providers = {
    alicloud.shared_service_account = alicloud.shared_service_account
    alicloud.vpc_account            = alicloud.prod_account
  }

  cen_tr_account_id     = local.shared_service_account_id
  vpc_account_id        = local.prod_account_id
  cen_instance_id       = local.cen_instance_id
  cen_transit_router_id = local.cen_transit_router_id
  vpc_id                = local.prod_account_vpc_id
  all_vpc_cidr          = local.all_vpc_cidr

  primary_vswitch = {
    vswitch_id = local.vpc_json.prod_account.vsw1_id,
    zone_id    = local.prod_account_vpc_config.vswitch.0.zone_id
  }

  secondary_vswitch = {
    vswitch_id = local.vpc_json.prod_account.vsw2_id,
    zone_id    = local.prod_account_vpc_config.vswitch.1.zone_id
  }

  route_table_association_enabled = true
  route_table_propagation_enabled = true

  depends_on = [module.dev_account_cen_attach]
}


provider "alicloud" {
  alias  = "ops_account"
  region = local.ops_account_vpc_config.region
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.ops_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

module "ops_account_cen_attach" {
  source    = "../../modules/cen-vpc-attach"
  providers = {
    alicloud.shared_service_account = alicloud.shared_service_account
    alicloud.vpc_account            = alicloud.ops_account
  }
  all_vpc_cidr          = local.all_vpc_cidr

  cen_tr_account_id     = local.shared_service_account_id
  vpc_account_id        = local.ops_account_id
  cen_instance_id       = local.cen_instance_id
  cen_transit_router_id = local.cen_transit_router_id
  vpc_id                = local.ops_account_vpc_id

  primary_vswitch = {
    vswitch_id = local.vpc_json.ops_account.vsw1_id,
    zone_id    = local.ops_account_vpc_config.vswitch.0.zone_id
  }

  secondary_vswitch = {
    vswitch_id = local.vpc_json.ops_account.vsw2_id,
    zone_id    = local.ops_account_vpc_config.vswitch.1.zone_id
  }

  route_table_association_enabled = true
  route_table_propagation_enabled = true

  depends_on = [module.prod_account_cen_attach]
}


# Save VPC information
resource "local_file" "account_json" {
  content    = templatefile("../var/cen.json.tmpl", {
    cen_instance_id       = local.cen_instance_id
    cen_transit_router_id = local.cen_transit_router_id

    shared_service_account_vpc_id        = local.shared_service_account_vpc_id
    shared_service_account_attachment_id = module.shared_service_account_cen_attach.attachment_id

    dev_account_vpc_id        = local.dev_account_vpc_id
    dev_account_attachment_id = module.dev_account_cen_attach.attachment_id

    prod_account_vpc_id        = local.prod_account_vpc_id
    prod_account_attachment_id = module.prod_account_cen_attach.attachment_id

    ops_account_vpc_id        = local.ops_account_vpc_id
    ops_account_attachment_id = module.ops_account_cen_attach.attachment_id


  })
  filename   = "../var/cen.json"
  depends_on = [
    module.shared_service_account_cen_attach, module.dev_account_cen_attach, module.prod_account_cen_attach,
    module.ops_account_cen_attach
  ]
}
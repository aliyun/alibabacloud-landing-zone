locals {
  account_json              = fileexists("../var/account.json") ? jsondecode(file("../var/account.json")) : {}
  shared_service_account_id = var.shared_service_account_id == "" ? local.account_json["shared_service_account_id"] : var.shared_service_account_id
  dev_account_id            = var.dev_account_id == "" ? local.account_json["dev_account_id"] : var.dev_account_id

  shared_service_account_vpc_config = var.shared_service_account_vpc_config
  dev_account_vpc_config            = var.dev_account_vpc_config

  vpc_json                      = fileexists("../var/vpc.json") ? jsondecode(file("../var/vpc.json")) : {}
  shared_service_account_vpc_id = var.shared_service_account_vpc_id == "" ? local.vpc_json["shared_service_account"]["vpc_id"] : var.shared_service_account_vpc_id
  dev_account_vpc_id            = var.dev_account_vpc_id == "" ? local.vpc_json["dev_account"]["vpc_id"] : var.dev_account_vpc_id
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

module "shared_service_account_ecs_alb" {
  source    = "../../modules/ecs-alb-multi-az"
  providers = {
    alicloud = alicloud.shared_service_account
  }

  vpc_id                     = local.shared_service_account_vpc_id
  security_group_name        = var.security_group_name
  security_group_desc        = var.security_group_desc
  security_group_rule        = var.security_group_rule
  ecs_instance_password      = var.ecs_instance_password
  ecs_instance_deploy_config = [
    {
      zone_id       = local.shared_service_account_vpc_config.vswitch.0.zone_id
      vswitch_id    = local.vpc_json.shared_service_account.vsw1_id
      instance_name = var.dmz_vpc_ecs_instance_deploy_config.0.instance_name
      host_name     = var.dmz_vpc_ecs_instance_deploy_config.0.host_name
      description   = var.dmz_vpc_ecs_instance_deploy_config.0.description
    }, {
      zone_id       = local.shared_service_account_vpc_config.vswitch.1.zone_id
      vswitch_id    = local.vpc_json.shared_service_account.vsw2_id
      instance_name = var.dmz_vpc_ecs_instance_deploy_config.1.instance_name
      host_name     = var.dmz_vpc_ecs_instance_deploy_config.1.host_name
      description   = var.dmz_vpc_ecs_instance_deploy_config.1.description
    }
  ]

  ecs_instance_spec = var.ecs_instance_spec

  alb_instance_deploy_config = {
    load_balancer_name = var.dmz_vpc_alb_instance_name
    zone_1_id          = local.shared_service_account_vpc_config.vswitch.0.zone_id
    vswitch_1_id       = local.vpc_json.shared_service_account.vsw1_id

    zone_2_id    = local.shared_service_account_vpc_config.vswitch.1.zone_id
    vswitch_2_id = local.vpc_json.shared_service_account.vsw2_id
  }

  alb_instance_spec        = var.alb_instance_spec
  server_group_config      = var.server_group_config
  alb_listener_description = var.alb_listener_description
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

module "dev_account_ecs_alb" {
  source    = "../../modules/ecs-alb-multi-az"
  providers = {
    alicloud = alicloud.dev_account
  }

  vpc_id                     = local.dev_account_vpc_id
  security_group_name        = var.security_group_name
  security_group_desc        = var.security_group_desc
  security_group_rule        = var.security_group_rule
  ecs_instance_password      = var.ecs_instance_password
  ecs_instance_deploy_config = [
    {
      zone_id       = local.dev_account_vpc_config.vswitch.0.zone_id
      vswitch_id    = local.vpc_json.dev_account.vsw1_id
      instance_name = var.dev_vpc_ecs_instance_deploy_config.0.instance_name
      host_name     = var.dev_vpc_ecs_instance_deploy_config.0.host_name
      description   = var.dev_vpc_ecs_instance_deploy_config.0.description
    }, {
      zone_id       = local.dev_account_vpc_config.vswitch.1.zone_id
      vswitch_id    = local.vpc_json.dev_account.vsw2_id
      instance_name = var.dev_vpc_ecs_instance_deploy_config.1.instance_name
      host_name     = var.dev_vpc_ecs_instance_deploy_config.1.host_name
      description   = var.dev_vpc_ecs_instance_deploy_config.1.description
    }
  ]

  alb_instance_deploy_config = {
    load_balancer_name = var.dev_vpc_alb_instance_name
    zone_1_id    = local.dev_account_vpc_config.vswitch.0.zone_id
    vswitch_1_id = local.vpc_json.dev_account.vsw1_id

    zone_2_id    = local.dev_account_vpc_config.vswitch.1.zone_id
    vswitch_2_id = local.vpc_json.dev_account.vsw2_id
  }
}


# Save  information
resource "local_file" "account_json" {
  content    = templatefile("../var/ecs-alb.json.tmpl", {
    shared_service_account_security_group_id = module.shared_service_account_ecs_alb.security_group_id
    shared_service_account_ecs1_id           = module.shared_service_account_ecs_alb.ecs_instance_ids.0
    shared_service_account_ecs2_id           = module.shared_service_account_ecs_alb.ecs_instance_ids.1
    shared_service_account_alb_id            = module.shared_service_account_ecs_alb.alb_instance_id
    shared_service_account_server_group_id   = module.shared_service_account_ecs_alb.server_group_id
    shared_service_account_alb_listener_id   = module.shared_service_account_ecs_alb.alb_listener_id

    dev_account_security_group_id = module.dev_account_ecs_alb.security_group_id
    dev_account_ecs1_id           = module.dev_account_ecs_alb.ecs_instance_ids.0
    dev_account_ecs2_id           = module.dev_account_ecs_alb.ecs_instance_ids.1
    dev_account_alb_id            = module.dev_account_ecs_alb.alb_instance_id
    dev_account_server_group_id   = module.dev_account_ecs_alb.server_group_id
    dev_account_alb_listener_id   = module.dev_account_ecs_alb.alb_listener_id
  })
  filename   = "../var/ecs-alb.json"
  depends_on = [
    module.shared_service_account_ecs_alb, module.dev_account_ecs_alb
  ]
}

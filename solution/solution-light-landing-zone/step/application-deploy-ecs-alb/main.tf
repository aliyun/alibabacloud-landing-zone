locals {
  account_json              = fileexists("../var/account.json") ? jsondecode(file("../var/account.json")) : {}
  shared_service_account_id = var.shared_service_account_id == "" ? local.account_json["shared_service_account_id"] : var.shared_service_account_id
  dev_account_id            = var.dev_account_id == "" ? local.account_json["dev_account_id"] : var.dev_account_id
  prod_account_id            = var.prod_account_id == "" ? local.account_json["prod_account_id"] : var.prod_account_id
  ops_account_id            = var.ops_account_id == "" ? local.account_json["ops_account_id"] : var.ops_account_id

  shared_service_account_vpc_config = var.shared_service_account_vpc_config
  dev_account_vpc_config            = var.dev_account_vpc_config
  prod_account_vpc_config         = var.prod_account_vpc_config
  ops_account_vpc_config         = var.ops_account_vpc_config


  vpc_json                      = fileexists("../var/vpc.json") ? jsondecode(file("../var/vpc.json")) : {}
  shared_service_account_vpc_id = var.shared_service_account_vpc_id == "" ? local.vpc_json["shared_service_account"]["vpc_id"] : var.shared_service_account_vpc_id
  prod_account_vpc_id            = var.prod_account_vpc_id == "" ? local.vpc_json["prod_account"]["vpc_id"] : var.prod_account_vpc_id
  dev_account_vpc_id            = var.dev_account_vpc_id == "" ? local.vpc_json["dev_account"]["vpc_id"] : var.dev_account_vpc_id
  ops_account_vpc_id            = var.ops_account_vpc_id == "" ? local.vpc_json["ops_account"]["vpc_id"] : var.ops_account_vpc_id

}

# For Network Connection check
provider "alicloud" {
  alias  = "ops_account"
  region = local.ops_account_vpc_config.region
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.ops_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

module "ops_account_ecs_alb" {
  source    = "../../modules/ecs-multi-az"
  providers = {
    alicloud = alicloud.ops_account
  }

  vpc_id                     = local.ops_account_vpc_id
  security_group_name        = var.security_group_name
  security_group_desc        = var.security_group_desc
  security_group_rule        = var.security_group_rule
  ecs_instance_password      = var.ecs_instance_password
  ecs_instance_deploy_config = [
    {
      zone_id       = local.ops_account_vpc_config.vswitch.0.zone_id
      vswitch_id    = local.vpc_json.ops_account.vsw1_id
      instance_name = var.ops_vpc_ecs_instance_deploy_config.0.instance_name
      host_name     = var.ops_vpc_ecs_instance_deploy_config.0.host_name
      description   = var.ops_vpc_ecs_instance_deploy_config.0.description
    }
  ]

  ecs_instance_spec = var.ecs_instance_spec

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

module "prod_account_ecs_alb" {
  source    = "../../modules/ecs-alb-multi-az"
  providers = {
    alicloud = alicloud.prod_account
  }

  vpc_id                     = local.prod_account_vpc_id
  security_group_name        = var.security_group_name
  security_group_desc        = var.security_group_desc
  security_group_rule        = var.security_group_rule
  ecs_instance_password      = var.ecs_instance_password
  ecs_instance_deploy_config = [
    {
      zone_id       = local.prod_account_vpc_config.vswitch.0.zone_id
      vswitch_id    = local.vpc_json.prod_account.vsw1_id
      instance_name = var.prod_vpc_ecs_instance_deploy_config.0.instance_name
      host_name     = var.prod_vpc_ecs_instance_deploy_config.0.host_name
      description   = var.prod_vpc_ecs_instance_deploy_config.0.description
    }, {
      zone_id       = local.prod_account_vpc_config.vswitch.1.zone_id
      vswitch_id    = local.vpc_json.prod_account.vsw2_id
      instance_name = var.prod_vpc_ecs_instance_deploy_config.1.instance_name
      host_name     = var.prod_vpc_ecs_instance_deploy_config.1.host_name
      description   = var.prod_vpc_ecs_instance_deploy_config.1.description
    }
  ]

  ecs_instance_spec = var.ecs_instance_spec

  alb_instance_deploy_config = {
    load_balancer_name = var.dmz_vpc_alb_instance_name
    zone_1_id          = local.prod_account_vpc_config.vswitch.0.zone_id
    vswitch_1_id       = local.vpc_json.prod_account.vsw1_id

    zone_2_id    = local.prod_account_vpc_config.vswitch.1.zone_id
    vswitch_2_id = local.vpc_json.prod_account.vsw2_id
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
    prod_account_security_group_id = module.prod_account_ecs_alb.security_group_id
    prod_account_ecs1_id           = module.prod_account_ecs_alb.ecs_instance_ids.0
    prod_account_ecs2_id           = module.prod_account_ecs_alb.ecs_instance_ids.1
    prod_account_alb_id            = module.prod_account_ecs_alb.alb_instance_id
    prod_account_server_group_id   = module.prod_account_ecs_alb.server_group_id
    prod_account_alb_listener_id   = module.prod_account_ecs_alb.alb_listener_id

    dev_account_security_group_id = module.dev_account_ecs_alb.security_group_id
    dev_account_ecs1_id           = module.dev_account_ecs_alb.ecs_instance_ids.0
    dev_account_ecs2_id           = module.dev_account_ecs_alb.ecs_instance_ids.1
    dev_account_alb_id            = module.dev_account_ecs_alb.alb_instance_id
    dev_account_server_group_id   = module.dev_account_ecs_alb.server_group_id
    dev_account_alb_listener_id   = module.dev_account_ecs_alb.alb_listener_id
  })
  filename   = "../var/ecs-alb.json"
  depends_on = [
    module.prod_account_ecs_alb, module.dev_account_ecs_alb
  ]
}

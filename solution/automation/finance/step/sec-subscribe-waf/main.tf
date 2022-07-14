locals {
  account_json              = fileexists("../var/account.json") ? jsondecode(file("../var/account.json")) : {}
  security_account_id       = var.security_account_id == "" ? local.account_json["security_account_id"] : var.security_account_id
  shared_service_account_id = var.shared_service_account_id == "" ? local.account_json["shared_service_account_id"] : var.shared_service_account_id
  dev_account_id            = var.dev_account_id == "" ? local.account_json["dev_account_id"] : var.dev_account_id

  alb_json                      = fileexists("../var/ecs-alb.json") ? jsondecode(file("../var/ecs-alb.json")) : {}
  dev_account_alb_id            = var.dev_account_alb_id == "" ? local.alb_json["dev_account"]["alb_id"] : var.dev_account_alb_id
  shared_service_account_alb_id = var.shared_service_account_alb_id == "" ? local.alb_json["shared_service_account"]["alb_id"] : var.shared_service_account_alb_id
}


provider "alicloud" {
  region = "cn-hangzhou"
#  alias  = "security_account"
#  assume_role {
#    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.security_account_id)
#    session_name       = "AccountLandingZoneSetup"
#    session_expiration = 999
#  }
}

resource "alicloud_waf_instance" "waf_instance" {
#  provider             = alicloud.security_account
  big_screen           = var.waf_instance_spec.big_screen
  exclusive_ip_package = var.waf_instance_spec.exclusive_ip_package
  ext_bandwidth        = var.waf_instance_spec.ext_bandwidth
  ext_domain_package   = var.waf_instance_spec.ext_domain_package
  package_code         = var.waf_instance_spec.package_code
  prefessional_service = var.waf_instance_spec.prefessional_service
  subscription_type    = var.waf_instance_spec.subscription_type
  period               = var.waf_instance_spec.period
  waf_log              = var.waf_instance_spec.waf_log
  log_storage          = var.waf_instance_spec.log_storage
  log_time             = var.waf_instance_spec.log_time
}

provider "alicloud" {
  region = var.region
  alias  = "shared_service_account"
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.shared_service_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

data "alicloud_alb_load_balancers" "alb_shared_service_account" {
  provider = alicloud.shared_service_account
  ids      = [local.shared_service_account_alb_id]
}

locals {
  shared_service_alb_instance           = data.alicloud_alb_load_balancers.alb_shared_service_account.balancers.0
  shared_service_alb_instance_zone_1_ip = local.shared_service_alb_instance.zone_mappings.0.load_balancer_addresses.0.address
  shared_service_alb_instance_zone_2_ip = local.shared_service_alb_instance.zone_mappings.1.load_balancer_addresses.0.address
  shared_service_account_domain_name    = var.shared_service_account_domain_name == "" ? local.shared_service_alb_instance.dns_name : var.shared_service_account_domain_name
}

resource "alicloud_waf_domain" "domain_shared_service_account" {
#  provider    = alicloud.security_account
  domain_name = local.shared_service_account_domain_name
  instance_id = alicloud_waf_instance.waf_instance.id
  source_ips  = [local.shared_service_alb_instance_zone_1_ip, local.shared_service_alb_instance_zone_2_ip]

  is_access_product = var.waf_domain_config.is_access_product
  http2_port        = var.waf_domain_config.http2_port
  http_port         = var.waf_domain_config.http_port
  https_port        = var.waf_domain_config.https_port
  http_to_user_ip   = var.waf_domain_config.http_to_user_ip
  https_redirect    = var.waf_domain_config.https_redirect
  load_balancing    = var.waf_domain_config.load_balancing
}


provider "alicloud" {
  region = var.region
  alias  = "dev_account"
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.dev_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

data "alicloud_alb_load_balancers" "alb_dev_account" {
  provider = alicloud.dev_account
  ids      = [local.dev_account_alb_id]
}

locals {
  dev_alb_instance           = data.alicloud_alb_load_balancers.alb_dev_account.balancers.0
  dev_alb_instance_zone_1_ip = local.dev_alb_instance.zone_mappings.0.load_balancer_addresses.0.address
  dev_alb_instance_zone_2_ip = local.dev_alb_instance.zone_mappings.1.load_balancer_addresses.0.address
  dev_account_domain_name    = var.dev_account_domain_name == "" ? local.dev_alb_instance.dns_name : var.dev_account_domain_name
}

resource "alicloud_waf_domain" "domain_dev_account" {
#  provider    = alicloud.security_account
  domain_name = local.dev_account_domain_name
  instance_id = alicloud_waf_instance.waf_instance.id
  source_ips  = [local.dev_alb_instance_zone_1_ip, local.dev_alb_instance_zone_2_ip]

  is_access_product = var.waf_domain_config.is_access_product
  http2_port        = var.waf_domain_config.http2_port
  http_port         = var.waf_domain_config.http_port
  https_port        = var.waf_domain_config.https_port
  http_to_user_ip   = var.waf_domain_config.http_to_user_ip
  https_redirect    = var.waf_domain_config.https_redirect
  load_balancing    = var.waf_domain_config.load_balancing
}

resource "local_file" "json_file" {
  content  = templatefile("../var/waf.json.tmpl", {
    waf_id                           = alicloud_waf_instance.waf_instance.id
    shared_service_account_waf_cname = alicloud_waf_domain.domain_shared_service_account.cname
    dev_account_waf_cname            = alicloud_waf_domain.domain_dev_account.cname
  })
  filename = "../var/waf.json"
}

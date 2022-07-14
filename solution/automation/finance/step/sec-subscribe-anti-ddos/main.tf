locals {
  account_json              = fileexists("../var/account.json") ? jsondecode(file("../var/account.json")) : {}
  dev_account_id            = var.dev_account_id == "" ? local.account_json["dev_account_id"] : var.dev_account_id
  security_account_id       = var.security_account_id == "" ? local.account_json["security_account_id"] : var.security_account_id
  shared_service_account_id = var.shared_service_account_id == "" ? local.account_json["shared_service_account_id"] : var.shared_service_account_id

  waf_security_account_json = fileexists("../var/waf.json") ? jsondecode(file("../var/waf.json"))["security_account"] : {}

  alb_json                      = fileexists("../var/ecs-alb.json") ? jsondecode(file("../var/ecs-alb.json")) : {}
  dev_account_alb_id            = var.dev_account_alb_id == "" ? local.alb_json["dev_account"]["alb_id"] : var.dev_account_alb_id
  shared_service_account_alb_id = var.shared_service_account_alb_id == "" ? local.alb_json["shared_service_account"]["alb_id"] : var.shared_service_account_alb_id

}

provider "alicloud" {
  region = "cn-hangzhou"
  #  alias = "security_account"
  #  assume_role {
  #    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.security_account_id)
  #    session_name       = "AccountLandingZoneSetup"
  #    session_expiration = 999
  #  }
}

resource "alicloud_ddoscoo_instance" "newInstance" {
  #  provider = alicloud.security_account
  name              = var.ddos_bgp_instance_spec.name
  bandwidth         = var.ddos_bgp_instance_spec.bandwidth
  base_bandwidth    = var.ddos_bgp_instance_spec.base_bandwidth
  service_bandwidth = var.ddos_bgp_instance_spec.service_bandwidth
  port_count        = var.ddos_bgp_instance_spec.port_count
  domain_count      = var.ddos_bgp_instance_spec.domain_count
  period            = var.ddos_bgp_instance_spec.period
  product_type      = var.ddos_bgp_instance_spec.product_type
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
  shared_service_alb_instance        = data.alicloud_alb_load_balancers.alb_shared_service_account.balancers.0
  shared_service_account_domain_name = var.shared_service_account_domain_name == "" ? local.shared_service_alb_instance.dns_name : var.shared_service_account_domain_name

  shared_service_account_real_servers = length(var.shared_service_account_real_servers) == 0 ? [
    local.waf_security_account_json["shared_service_account_waf_cname"]
  ] : var.shared_service_account_real_servers
}


resource "alicloud_ddoscoo_domain_resource" "shared_service_account_domain_resource" {
  #  provider = alicloud.security_account
  instance_ids = [alicloud_ddoscoo_instance.newInstance.id]
  real_servers = local.shared_service_account_real_servers
  domain       = local.shared_service_account_domain_name
  https_ext    = var.ddos_domain_https_ext
  rs_type      = var.ddos_domain_rs_type

  dynamic "proxy_types" {
    for_each = {for type in var.ddos_domain_proxy_types : type.proxy_type => type}
    content {
      proxy_ports = proxy_types.value.proxy_ports
      proxy_type  = proxy_types.value.proxy_type
    }
  }
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
  dev_alb_instance        = data.alicloud_alb_load_balancers.alb_dev_account.balancers.0
  dev_account_domain_name = var.dev_account_domain_name == "" ? local.dev_alb_instance.dns_name : var.dev_account_domain_name

  dev_account_real_servers = length(var.dev_account_real_servers) == 0 ? [
    local.waf_security_account_json["dev_account_waf_cname"]
  ] : var.dev_account_real_servers

}

resource "alicloud_ddoscoo_domain_resource" "dev_account_domain_resource" {
  #  provider = alicloud.security_account
  instance_ids = [alicloud_ddoscoo_instance.newInstance.id]
  real_servers = local.dev_account_real_servers
  domain       = local.dev_account_domain_name
  https_ext    = var.ddos_domain_https_ext
  rs_type      = var.ddos_domain_rs_type

  dynamic "proxy_types" {
    for_each = {for type in var.ddos_domain_proxy_types : type.proxy_type => type}
    content {
      proxy_ports = proxy_types.value.proxy_ports
      proxy_type  = proxy_types.value.proxy_type
    }
  }
}

resource "local_file" "json_file" {
  content  = templatefile("../var/anti-ddos.json.tmpl", {
    anti_ddos_id                     = alicloud_ddoscoo_instance.newInstance.id
    dev_account_waf_domain_id        = alicloud_ddoscoo_domain_resource.dev_account_domain_resource.id
    shared_service_account_domain_id = alicloud_ddoscoo_domain_resource.shared_service_account_domain_resource.id
  })
  filename = "../var/anti-ddos.json"
}


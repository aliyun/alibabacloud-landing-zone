resource "alicloud_security_group" "group" {
  name        = var.security_group_name
  description = var.security_group_desc
  vpc_id      = var.vpc_id
}

resource "alicloud_security_group_rule" "security_group_rule" {
  count             = length(var.security_group_rule)
  security_group_id = alicloud_security_group.group.id

  type        = var.security_group_rule[count.index].type
  ip_protocol = var.security_group_rule[count.index].ip_protocol
  nic_type    = var.security_group_rule[count.index].nic_type
  policy      = var.security_group_rule[count.index].policy
  port_range  = var.security_group_rule[count.index].port_range
  priority    = var.security_group_rule[count.index].priority
  cidr_ip     = var.security_group_rule[count.index].cidr_ip
}

resource "alicloud_instance" "instances" {
  count = length(var.ecs_instance_deploy_config)

  resource_group_id = var.resource_group_id
  security_groups   = alicloud_security_group.group.*.id

  vswitch_id    = var.ecs_instance_deploy_config[count.index].vswitch_id
  instance_name = var.ecs_instance_deploy_config[count.index].instance_name
  description   = var.ecs_instance_deploy_config[count.index].description
  host_name     = var.ecs_instance_deploy_config[count.index].host_name
  password      = var.ecs_instance_password

  instance_type              = var.ecs_instance_spec.instance_type
  system_disk_category       = var.ecs_instance_spec.system_disk_category
  image_id                   = var.ecs_instance_spec.image_id
  instance_charge_type       = var.ecs_instance_spec.instance_charge_type
  period_unit                = var.ecs_instance_spec.period_unit
  period                     = var.ecs_instance_spec.period
  internet_max_bandwidth_out = var.ecs_instance_spec.internet_max_bandwidth_out
  tags                       = var.ecs_instance_spec.tags
  volume_tags                = var.ecs_instance_spec.volume_tags
}

resource "alicloud_alb_load_balancer" "default" {
  resource_group_id = var.resource_group_id
  vpc_id            = var.vpc_id

  load_balancer_name     = var.alb_instance_deploy_config.load_balancer_name
  address_type           = var.alb_instance_spec.address_type
  address_allocated_mode = var.alb_instance_spec.address_allocated_mode
  load_balancer_edition  = var.alb_instance_spec.load_balancer_edition

  load_balancer_billing_config {
    pay_type = "PayAsYouGo"
  }

  zone_mappings {
    vswitch_id = var.alb_instance_deploy_config.vswitch_1_id
    zone_id    = var.alb_instance_deploy_config.zone_1_id
  }
  zone_mappings {
    vswitch_id = var.alb_instance_deploy_config.vswitch_2_id
    zone_id    = var.alb_instance_deploy_config.zone_2_id
  }

  modification_protection_config {
    status = "NonProtection"
  }

  tags = var.alb_instance_spec.tags
}


resource "alicloud_alb_server_group" "default" {
  vpc_id            = var.vpc_id
  resource_group_id = var.resource_group_id

  protocol          = var.server_group_config.protocol
  server_group_name = var.server_group_config.server_group_name
  tags              = var.server_group_config.tags

  health_check_config {
    health_check_connect_port = var.server_group_config.health_check_connect_port
    health_check_enabled      = var.server_group_config.health_check_enabled
    health_check_host         = "$SERVER_IP"
    health_check_codes        = var.server_group_config.health_check_codes
    health_check_http_version = var.server_group_config.health_check_http_version
    health_check_interval     = var.server_group_config.health_check_interval
    health_check_method       = var.server_group_config.health_check_method
    health_check_path         = var.server_group_config.health_check_path
    health_check_protocol     = var.server_group_config.health_check_protocol
    health_check_timeout      = var.server_group_config.health_check_timeout
    healthy_threshold         = var.server_group_config.healthy_threshold
    unhealthy_threshold       = var.server_group_config.unhealthy_threshold
  }

  sticky_session_config {
    sticky_session_enabled = var.server_group_config.sticky_session_enabled
    cookie                 = var.server_group_config.cookie
    cookie_timeout         = var.server_group_config.cookie_timeout
    sticky_session_type    = var.server_group_config.sticky_session_type
  }

  dynamic "servers" {
    for_each = alicloud_instance.instances
    content {
      server_type = "Ecs"
      server_id   = servers.value.id
      server_ip   = servers.value.private_ip
      description = servers.value.instance_name
      weight      = var.server_group_config.weight
      port        = var.server_group_config.port
    }
  }
}

resource "alicloud_alb_listener" "default" {
  load_balancer_id     = alicloud_alb_load_balancer.default.id
  listener_protocol    = var.server_group_config.protocol
  listener_port        = var.server_group_config.port
  listener_description = var.alb_listener_description
  default_actions {
    type = "ForwardGroup"
    forward_group_config {
      server_group_tuples {
        server_group_id = alicloud_alb_server_group.default.id
      }
    }
  }
}



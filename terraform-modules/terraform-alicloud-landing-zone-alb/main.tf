
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
    for_each = var.server_group_backend_servers
    content {
      server_id   = servers.value.server_id
      server_type = servers.value.server_type
      server_ip   = servers.value.server_ip
      description = servers.value.description
      weight      = servers.value.weight
      port        = servers.value.port
    }
  }
}

resource "alicloud_alb_listener" "default" {
  load_balancer_id     = alicloud_alb_load_balancer.default.id
  listener_protocol    = var.alb_listener_config.listener_protocol
  listener_port        = var.alb_listener_config.listener_port
  listener_description = var.alb_listener_config.listener_description
  default_actions {
    type = "ForwardGroup"
    forward_group_config {
      server_group_tuples {
        server_group_id = alicloud_alb_server_group.default.id
      }
    }
  }
}



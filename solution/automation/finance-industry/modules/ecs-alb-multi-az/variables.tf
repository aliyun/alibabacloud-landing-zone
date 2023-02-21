variable "vpc_id" {
  description = "The ID of the VPC."
  type        = string
}

variable "resource_group_id" {
  description = "The ID of the resource group."
  type        = string
  default     = null
}

variable "security_group_name" {
  description = "The name of the new security group."
  type        = string
  default     = "sg-tf-default"
}

variable "security_group_desc" {
  description = "Description of the new security group."
  type        = string
  default     = "sg-tf-default"
}

variable "security_group_rule" {
  type = list(object({
    type        = string
    ip_protocol = string
    nic_type    = string
    policy      = string
    port_range  = string
    priority    = number
    cidr_ip     = string
  }))

  default = [
    {
      type        = "ingress"
      ip_protocol = "tcp"
      nic_type    = "intranet"
      policy      = "accept"
      port_range  = "1/65535"
      priority    = 1
      cidr_ip     = "0.0.0.0/0"
    }
  ]
}

variable "ecs_instance_password" {}

variable "ecs_instance_deploy_config" {
  type = list(object({
    zone_id       = string
    vswitch_id    = string
    instance_name = string
    host_name     = string
    description   = string
  }))
}

variable "ecs_instance_spec" {
  type = object({
    instance_type              = string
    system_disk_category       = string
    image_id                   = string
    instance_charge_type       = string
    period_unit                = string
    period                     = number
    internet_max_bandwidth_out = number
    tags                       = map(string)
    volume_tags                = map(string)
  })

  default = {
    instance_type              = "ecs.t5-lc1m1.small"
    system_disk_category       = "cloud_efficiency"
    image_id                   = "centos_8_5_x64_20G_alibase_20220428.vhd"
    instance_charge_type       = "PostPaid"
    period_unit                = "Month"
    period                     = 1
    internet_max_bandwidth_out = 0
    tags                       = { createdBy : "Terraform" }
    volume_tags                = { createdBy : "Terraform" }
  }
}

variable "alb_instance_deploy_config" {
  type = object({
    load_balancer_name = string

    zone_1_id    = string
    vswitch_1_id = string

    zone_2_id    = string
    vswitch_2_id = string
  })
}

variable "alb_instance_spec" {
  type = object({
    protocol               = string
    address_type           = string
    address_allocated_mode = string
    load_balancer_edition  = string
    tags                   = map(string)
  })

  default = {
    protocol               = "HTTP"
    address_type           = "Internet"
    address_allocated_mode = "Fixed"
    load_balancer_edition  = "Basic"
    tags                   = { createdBy : "Terraform" }
  }
}

variable "server_group_config" {
  type = object({
    server_group_name = string
    protocol          = string
    tags              = map(string)

    # health check config
    health_check_connect_port = string
    health_check_enabled      = bool
    health_check_codes        = list(string)
    health_check_http_version = string
    health_check_interval     = string
    health_check_method       = string
    health_check_path         = string
    health_check_protocol     = string
    health_check_timeout      = number
    healthy_threshold         = number
    unhealthy_threshold       = number

    # sticky session config
    sticky_session_enabled = bool
    cookie                 = string
    cookie_timeout         = number
    sticky_session_type    = string

    # server config
    port   = number
    weight = number
  })

  default = {
    server_group_name = "server-group-tf"
    protocol          = "HTTP"
    tags              = { createdBy : "Terraform" }

    health_check_protocol     = "HTTP"
    health_check_connect_port = "80"
    health_check_enabled      = true
    health_check_codes        = ["http_2xx", "http_3xx", "http_4xx"]
    health_check_http_version = "HTTP1.1"
    health_check_interval     = "2"
    health_check_method       = "GET"
    health_check_path         = "/hello_landing_zone"
    health_check_timeout      = 5
    healthy_threshold         = 3
    unhealthy_threshold       = 3

    sticky_session_enabled = false
    cookie                 = null
    cookie_timeout         = 1000
    sticky_session_type    = "Insert"

    port   = 80
    weight = 100
  }
}

variable "alb_listener_description" {
  description = "The description of the listener."
  type        = string
  default     = "createdByTerraform"
}

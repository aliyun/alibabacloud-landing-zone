variable "shared_service_account_id" {
  type    = string
  default = ""
}

variable "dev_account_id" {
  type    = string
  default = ""
}

variable "prod_account_id" {
  type    = string
  default = ""
}

variable "ops_account_id" {
  type    = string
  default = ""
}

variable "shared_service_account_vpc_config" {}
variable "dev_account_vpc_config" {}
variable "prod_account_vpc_config" {}
variable "ops_account_vpc_config" {}

variable "shared_service_account_vpc_id" {
  type    = string
  default = ""
}

variable "dev_account_vpc_id" {
  type    = string
  default = ""
}

variable "prod_account_vpc_id" {
  type    = string
  default = ""
}

variable "ops_account_vpc_id" {
  type    = string
  default = ""
}

variable "security_group_name" {}
variable "security_group_desc" {}
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

variable "dmz_vpc_ecs_instance_deploy_config" {
  type = list(object({
    instance_name = string
    host_name     = string
    description   = string
  }))
}

variable "dev_vpc_ecs_instance_deploy_config" {
  type = list(object({
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

variable "dmz_vpc_alb_instance_name" {
  type    = string
  default = "alb-tf-default"
}

variable "dev_vpc_alb_instance_name" {
  type    = string
  default = "alb-tf-default"
}

variable "alb_instance_spec" {
  type = object({
    protocol               = string
    address_type           = string
    address_allocated_mode = string
    load_balancer_name     = string
    load_balancer_edition  = string
    tags                   = map(string)
  })

  default = {
    protocol               = "HTTP"
    address_type           = "Internet"
    address_allocated_mode = "Fixed"
    load_balancer_name     = "alb-tf-default"
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


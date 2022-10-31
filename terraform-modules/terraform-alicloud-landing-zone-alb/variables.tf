variable "vpc_id" {
  description = "The ID of the VPC."
  type        = string
}

variable "resource_group_id" {
  description = "The ID of the resource group."
  type        = string
  default     = null
}

variable "alb_instance_deploy_config" {
  description = "Deployment configuration of the ALB instance."

  type = object({
    load_balancer_name = string

    zone_1_id    = string
    vswitch_1_id = string

    zone_2_id    = string
    vswitch_2_id = string
  })
}

variable "alb_instance_spec" {
  description = "Specification of the ALB instance."

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
  description = "Configuration of the server group."

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

    port = 80
  }
}

variable "server_group_backend_servers" {
  description = "Configuration of the backend server."

  type = list(object({
    server_type = string
    server_id   = string
    server_ip   = string
    description = string
    weight      = number
    port        = number
  }))

  default = []
}

variable "alb_listener_config" {
  description = "Configuration of the ALB Listener."
  type        = object({
    listener_protocol    = string
    listener_port        = number
    listener_description = string
  })

  default = {
    listener_protocol    = "HTTP"
    listener_port        = 80
    listener_description = "HTTP_80"
  }
}



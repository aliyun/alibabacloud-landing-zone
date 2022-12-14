variable "region" {
  type = string
}

variable "dmz_vpc_id" {
  description = "VPC ID of the DMZ VPC."
  type        = string
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

variable "alb_instance_deploy_config" {
  type = object({
    load_balancer_name = string

    zone_1_id    = string
    vswitch_1_id = string

    zone_2_id    = string
    vswitch_2_id = string
  })
}

# not support yet
variable "server_group_backend_servers" {
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
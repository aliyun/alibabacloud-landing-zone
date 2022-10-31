variable "region" {
  type = string
}

variable "dmz_vpc_id" {
  description = "VPC ID of the DMZ VPC."
  type        = string
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
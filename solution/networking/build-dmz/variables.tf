variable "shared_service_account_id" {
  type = string
}

variable "biz_vpc_1_account_id" {
  type = string
}

variable "biz_vpc_2_account_id" {
  type = string
}

variable "region" {
  type    = string
  default = "cn-shanghai"
}

variable "dmz_vpc_id" {
  description = "VPC ID of the DMZ VPC."
  type        = string
}

variable "nat_gateway_config" {
  description = "VPC ID of the DMZ VPC."
  type        = object({
    name                  = string
    vswitch_id            = string
    snat_source_cidr_list = list(string)
  })
}

variable "transit_router_id" {
  description = "Transit router ID."
}

variable "cen_attach_id_dmz_vpc" {
  description = "Attachment ID of the DMZ VPC."
  type        = string
}

variable "biz_vpc_1_id" {
  description = "ID of the business VPC1."
  type        = string
}

variable "biz_vpc_1_cidr" {
  description = "CIDR of the business VPC1."
  type        = string
}

variable "cen_attach_id_biz_vpc_1" {
  description = "Attachment ID of VPC1."
  type        = string
}

variable "biz_vpc_2_id" {
  description = "ID of the business VPC2."
  type        = string
}

variable "cen_attach_id_biz_vpc_2" {
  description = "Attachment ID of VPC2."
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

variable "alb_back_to_source_route" {
  description = "ALB back to source routing."
  type        = list(string)
  default     = []
}




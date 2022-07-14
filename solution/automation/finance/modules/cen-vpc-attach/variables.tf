variable "cen_tr_account_id" {}
variable "vpc_account_id" {}

variable "cen_instance_id" {
  description = "The ID of the CEN instance"
  type        = string
}

variable "cen_transit_router_id" {
  description = "The ID of the CEN transit router"
  type        = string
}

variable "transit_router_attachment_name" {
  type    = string
  default = ""
}

variable "transit_router_attachment_desc" {
  type    = string
  default = ""
}

variable "all_vpc_cidr" {
  description = "The network segment that contains all vpcs, used for default route connectivity."
  type        = string
  default     = ""
}

variable "vpc_id" {
  description = "The ID of the vpc to attach."
  type        = string
}

variable "primary_vswitch" {
  type = object({
    vswitch_id = string
    zone_id    = string
  })
}

variable "secondary_vswitch" {
  type = object({
    vswitch_id = string
    zone_id    = string
  })
}

variable "route_table_association_enabled" {
  type    = bool
  default = false
}

variable "route_table_propagation_enabled" {
  type    = bool
  default = false
}









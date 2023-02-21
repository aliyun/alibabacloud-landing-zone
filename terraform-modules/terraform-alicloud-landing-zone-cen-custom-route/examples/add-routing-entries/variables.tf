variable "region" {
  type = string
}

variable "create_route_table" {
  description = "Whether to create a TR route table. If false, use default route table."
  type        = bool
  default     = false
}

variable "transit_router_id" {
  description = "ID of the transit router."
  type        = string
}

variable "cen_attach_id_dmz_vpc" {
  description = "Attachment ID of the transit router association for DMZ VPC."
  type        = string
}

variable "alb_back_to_source_route" {
  description = "Configuration of the route entry."
  type        = list(string)
}

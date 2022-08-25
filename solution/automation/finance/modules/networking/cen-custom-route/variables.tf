variable "transit_router_id" {
  description = "ID of the transit router."
  type = string
}
variable "transit_router_route_table_name" {
  description = "Name of the custom route table."
  type = string
}

variable "transit_router_association_attachment_ids" {
  description = "Attachment ID of the transit router association."
  type        = list(string)
}

variable "transit_router_route_entry_config" {
  description = "Configuration of the route entry"
  type        = list(object({
    route_entry_dest_cidr     = string
    route_entry_next_hop_type = string
    route_entry_name          = string
    route_entry_description   = string
    route_entry_next_hop_id   = string
  }))
}


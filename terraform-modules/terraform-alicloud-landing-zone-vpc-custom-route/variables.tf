variable "vpc_id" {}

variable "create_route_table" {
  description = "Whether to create a VPC route table. If false, use system route table."
  type        = bool
  default     = false
}

variable "route_table_name" {
  description = "Name of the route table, fill in if you need to create a VPC route table."
  type        = string
  default     = "Custom route table"
}

variable "route_table_description" {
  description = "Description of the route table, fill in if you need to create a VPC route table."
  type        = string
  default     = "Custom route table"
}

variable "vswitch_id" {
  description = "VSwitch ID attached to the route table, fill in if you need to create a VPC route table."
  type        = string
  default     = ""
}

variable "route_entry_config" {
  description = "Routing entries for the system route table or custom route table."
  type        = list(object({
    name                  = string
    destination_cidrblock = string
    nexthop_type          = string
    nexthop_id            = string
  }))
}


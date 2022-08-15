variable "vpc_id" {}

variable "route_entry_config" {
  description = "The ID of the CEN transit router"
  type        = list(object({
    destination_cidrblock = string
    nexthop_type          = string
    nexthop_id            = string
  }))
}


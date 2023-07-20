variable "region" {
  type        = string
}

variable "dmz_vpc_cidr_block" {
  type        = string
}

variable "dmz_vswitch_list" {
  type = list(object({
    zone_id = string
    vswitch_cidr = string
  }))
}

variable "dmz_vswitch_for_tr" {
  type = list(object({
    zone_id = string
    vswitch_cidr = string
  }))
  description = "Vswitches in DMZ vpc for Transit Router. Recommend a segment of /29. Should have 2 vswitches."
  default = [
    {
      vswitch_cidr = "10.0.0.0/29"
      zone_id = ""
    },
    {
      vswitch_cidr = "10.0.0.8/29"
      zone_id = ""
    }
  ]
}

variable "dmz_vswitch_for_nat_gateway" {
  type = object({
    zone_id = string
    vswitch_cidr = string
  })
  description = "Vswitch for Enhanced NAT gateway."
  default = {
    vswitch_cidr = "10.0.0.64/26"
    zone_id = ""
  }
}


variable "dmz_egress_eip_count" {
  type = number
  default = 1
}

variable "dmz_enable_common_bandwidth_package" {
  type = bool
  default = true
}

variable "dmz_common_bandwidth_package_bandwidth" {
  type = number
  default = 1
}
variable "cen_instance_name" {
  type = string
  description = "The name of CEN instance."
  default = "landingzone-cen"
}

variable "cen_instance_description" {
  type = string
  description = "The description of CEN instance."
  default = "landingzone cen for global connectivity"
}

variable "cen_transit_router_name" {
  type = string
  description = "The name of TR for DMZ vpc attachment."
  default = "landingzone-tr"
}

variable "dmz_vpc_name" {
  type = string
  description = "The name of DMZ vpc."
  default = "dmz-vpc"
}

variable "dmz_vpc_description" {
  type = string
  description = "The description of DMZ vpc."
  default = "VPC used as DMZ"
}

variable "dmz_vpc_cidr" {
  type        = string
  description = "DMZ vpc cidr block."
  default     = "10.0.0.0/24"
}

variable "dmz_egress_nat_gateway_name" {
  type        = string
  description = "The name of NAT gateway instance for outbound."
  default = "dmz-egress-nat-gateway"
}

variable "dmz_egress_eip_count" {
  type = number
  description = "The number of EIP instances for outbound."
  default = 1
}

variable "dmz_egress_eip_name_prefix" {
  type        = string
  description = "EIP instance name prefix."
  default = "dmz-egress-eip-"
}

variable "dmz_enable_common_bandwidth_package" {
  type = bool
  description = "Whether to enable common bandwidth package for all EIP instances."
  default = true
}

variable "dmz_common_bandwidth_package_bandwidth" {
  type = number
  description = "The bandwidth for DMZ outbound."
  default = 5
}

variable "dmz_vswitch_for_tr" {
  type = list(object({
    zone_id = string
    vswitch_name = string
    vswitch_description = string
    vswitch_cidr = string
  }))
  description = "Vswitches in DMZ vpc for Transit Router. Recommend a segment of /29. Should have 2 vswitches."
  default = [
    {
      vswitch_cidr = "10.0.0.0/29"
      vswitch_description = "VSwitch 1 used for DMZ VPC TR"
      vswitch_name = "dmz-vswitch-tr-1"
      zone_id = ""
    },
    {
      vswitch_cidr = "10.0.0.8/29"
      vswitch_description = "VSwitch 2 used for DMZ VPC TR"
      vswitch_name = "dmz-vswitch-tr-2"
      zone_id = ""
    }
  ]
}

variable "dmz_vswitch_for_nat_gateway" {
  type = object({
    zone_id = string
    vswitch_name = string
    vswitch_description = string
    vswitch_cidr = string
  })
  description = "Vswitch for Enhanced NAT gateway."
  default = {
    vswitch_cidr = "10.0.0.64/26"
    vswitch_description = "VSwitch used for DMZ VPC NAT gateway"
    vswitch_name = "dmz-vswitch-nat-1"
    zone_id = ""
  }
}

variable "dmz_vswitch" {
  type = list(object({
    zone_id = string
    vswitch_name = string
    vswitch_description = string
    vswitch_cidr = string
  }))
  description = "Vswitches in DMZ vpc. Should be more than 2."
  default = [
    {
      vswitch_cidr = "10.0.1.0/24"
      vswitch_description = "VSwitch 1 used for DMZ VPC"
      vswitch_name = "dmz-vswitch-1"
      zone_id = ""
    },
    {
      vswitch_cidr = "10.0.2.0/24"
      vswitch_description = "VSwitch 2 used for DMZ VPC"
      vswitch_name = "dmz-vswitch-2"
      zone_id = ""
    }
  ]
}


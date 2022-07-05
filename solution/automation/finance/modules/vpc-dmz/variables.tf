variable "vpc_id" {
  description = "The vpc id used to launch several vswitches."
  type        = string
  default     = ""
}

variable "all_vpc_cidr" {
  description = "The network segment that contains all vpcs."
  type        = string
  default     = ""
}

variable "nat_gateway_name" {
  description = "The name of NAT Gateway."
  default     = "nat-gateway-dmz"
}

variable "nat_gateway_vswitch_id" {
  description = "The VSwitch ID for NAT gateway."
  type        = string
  default     = ""
}

variable "eip_address_name" {
  description = "The name of the EIP instance."
  type        = string
  default     = "eip-dmz"
}







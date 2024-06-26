variable "region" {
  type = string
}

variable "dmz_vpc_id" {
  description = "VPC ID of the DMZ VPC."
  type        = string
}

variable "nat_gateway_name" {
  type = string
  default = "dmz-nat-gateway-unified-egress"
}

variable "vswitch_id_nat_gateway" {
  description = "ID of the vSwitch to which the NAT gateway belongs."
  type = string
}

variable "snat_source_cidr_list" {
  description = "CIDR for SNAT."
  type = list(string)
}
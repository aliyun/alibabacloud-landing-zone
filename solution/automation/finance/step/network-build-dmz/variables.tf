variable "shared_service_account_id" {
  type    = string
  default = ""
}

variable "all_vpc_cidr" {
  type    = string
  default = ""
}

variable "shared_service_account_vpc_config" {}

variable "dmz_egress_nat_gateway_name" {
  type    = string
  default = ""
}

variable "dmz_egress_eip_name" {
  type    = string
  default = ""
}

variable "shared_service_account_vpc_id" {
  type    = string
  default = ""
}

variable "shared_service_account_vswitch_id" {
  type    = string
  default = ""
}



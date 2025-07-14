variable "shared_service_account_id" {
  type    = string
  default = ""
}
variable "dev_account_id" {
  type    = string
  default = ""
}
variable "prod_account_id" {
  type    = string
  default = ""
}

variable "shared_service_account_vpc_config" {}

variable "dev_account_vpc_config" {}

variable "prod_account_vpc_config" {}

variable "ops_account_vpc_config" {}

variable "prod_egress_nat_gateway_name" {
  type    = string
  default = ""
}

variable "daily_egress_nat_gateway_name" {
  type    = string
  default = ""
}

variable "prod_egress_eip_name" {
  type    = string
  default = ""
}
variable "daily_egress_eip_name" {
  type    = string
  default = ""
}

variable "dev_account_vpc_id" {
  type    = string
  default = ""
}

variable "dev_account_vswitch_id" {
  type    = string
  default = ""
}

variable "prod_account_vpc_id" {
  type    = string
  default = ""
}

variable "prod_account_vswitch_id" {
  type    = string
  default = ""
}



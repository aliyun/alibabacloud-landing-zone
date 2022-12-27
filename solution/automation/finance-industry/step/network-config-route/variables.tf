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

variable "ops_account_id" {
  type    = string
  default = ""
}

variable "shared_service_account_vpc_config" {}
variable "dev_account_vpc_config" {}
variable "prod_account_vpc_config" {}
variable "ops_account_vpc_config" {}

variable "shared_service_account_vpc_id" {
  type    = string
  default = ""
}

variable "dev_account_vpc_id" {
  type    = string
  default = ""
}

variable "prod_account_vpc_id" {
  type    = string
  default = ""
}

variable "ops_account_vpc_id" {
  type    = string
  default = ""
}

variable "shared_service_account_vpc_attachment_id" {
  type    = string
  default = ""
}

variable "dev_account_vpc_attachment_id" {
  type    = string
  default = ""
}

variable "prod_account_vpc_attachment_id" {
  type    = string
  default = ""
}

variable "ops_account_vpc_attachment_id" {
  type    = string
  default = ""
}

variable "transit_router_id" {
  type    = string
  default = ""
}




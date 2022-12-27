variable "management_account_id" {
    type    = string
    default = ""
}

variable "shared_service_account_id" {
    type    = string
    default = ""
}

variable "log_account_id" {
    type    = string
    default = ""
}

variable "security_account_id" {
    type    = string
    default = ""
}

variable "ops_account_id" {
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



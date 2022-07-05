variable "cen_instance_name" {
  description = "The name of the CEN instance"
  type        = string
}

variable "cen_instance_desc" {
  description = "The description of the CEN instance"
  type        = string
  default     = ""
}

variable "cen_instance_tags" {
  description = "A mapping of tags to assign to the resource"
  type        = map(string)
  default     = {}
}

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

variable "all_vpc_cidr" {
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



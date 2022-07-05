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

variable "ram_user_initial_pwd" {}

variable "sso_provider_name" {}

variable "management_account_ram_users" {}

variable "management_account_ram_roles" {}

variable "log_account_ram_users" {}

variable "log_account_ram_roles" {}

variable "shared_service_account_ram_users" {}

variable "shared_service_account_ram_roles" {}

variable "security_account_ram_users" {}

variable "security_account_ram_roles" {}

variable "ops_account_ram_users" {}

variable "ops_account_ram_roles" {}

variable "dev_account_ram_users" {}

variable "dev_account_ram_roles" {}

variable "prod_account_ram_users" {}

variable "prod_account_ram_roles" {}


variable "management_account_id" {
  type    = string
  default = ""
  description = "UID of the management account."
}

variable "shared_service_account_id" {
  type    = string
  default = ""
  description = "UID of the shared service account."
}

variable "log_account_id" {
  type    = string
  default = ""
  description = "UID of the log account."
}

variable "security_account_id" {
  type    = string
  default = ""
  description = "UID of the security account."
}

variable "ops_account_id" {
  type    = string
  default = ""
  description = "UID of the ops account."
}

variable "dev_account_id" {
  type    = string
  default = ""
  description = "UID of the development account."
}

variable "prod_account_id" {
  type    = string
  default = ""
  description = "UID of the production account."
}

variable "ram_user_initial_pwd" {
  type = string
  description = "Initial password of the RAM user."
}

variable "management_account_ram_users" {
    type    = list(object({
      name                 = string
      description          = string
      enable_console_login = bool
      enable_api_access    = bool
    }))
  description = "RAM user configuration of the management account."
}



variable "log_account_ram_users" {
  type    = list(object({
    name                 = string
    description          = string
    enable_console_login = bool
    enable_api_access    = bool
  }))
  description = "RAM user configuration of the log account."
}

variable "shared_service_account_ram_users" {
  type    = list(object({
    name                 = string
    description          = string
    enable_console_login = bool
    enable_api_access    = bool
  }))
  description = "RAM user configuration of the shared service account."
}


variable "security_account_ram_users" {
  type    = list(object({
    name                 = string
    description          = string
    enable_console_login = bool
    enable_api_access    = bool
  }))
  description = "RAM user configuration of the security account."
}


variable "ops_account_ram_users" {
  type    = list(object({
    name                 = string
    description          = string
    enable_console_login = bool
    enable_api_access    = bool
  }))
  description = "RAM user configuration of the ops account."
}



variable "dev_account_ram_users" {
  type    = list(object({
    name                 = string
    description          = string
    enable_console_login = bool
    enable_api_access    = bool
  }))
  description = "RAM user configuration of the development account."
}


variable "prod_account_ram_users" {
  type    = list(object({
    name                 = string
    description          = string
    enable_console_login = bool
    enable_api_access    = bool
  }))
  description = "RAM user configuration of the production account."
}



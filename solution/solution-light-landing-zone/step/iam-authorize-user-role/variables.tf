variable "management_account_id" {
  type        = string
  default     = ""
  description = "Management account ID of the resource directory"
}

variable "shared_service_account_id" {
  type        = string
  default     = ""
  description = "Shared service account ID"
}

variable "log_account_id" {
  type        = string
  default     = ""
  description = "Log account ID"
}

variable "security_account_id" {
  type        = string
  default     = ""
  description = "Security account ID"
}

variable "ops_account_id" {
  type        = string
  default     = ""
  description = "Ops account ID"
}

variable "dev_account_id" {
  type        = string
  default     = ""
  description = "Development account ID"
}

variable "prod_account_id" {
  type        = string
  default     = ""
  description = "Production account ID"
}

variable "ram_user_initial_pwd" {
  type        = string
  description = "Initial password for RAM user"
}



variable "management_account_ram_users" {
  type        = list(object({
    name                 = string
    description          = string
    enable_console_login = bool
    enable_api_access    = bool
    system_policy        = list(string)
  }))
  description = "RAM users to be created in management account"
}


variable "log_account_ram_users" {
  type        = list(object({
    name                 = string
    description          = string
    enable_console_login = bool
    enable_api_access    = bool
    system_policy        = list(string)
  }))
  description = "RAM users to be created in log account"
}



variable "shared_service_account_ram_users" {
  type        = list(object({
    name                 = string
    description          = string
    enable_console_login = bool
    enable_api_access    = bool
    system_policy        = list(string)
  }))
  description = "RAM users to be created in shared service account"
}



variable "security_account_ram_users" {
  type        = list(object({
    name                 = string
    description          = string
    enable_console_login = bool
    enable_api_access    = bool
    system_policy        = list(string)
  }))
  description = "RAM users to be created in security account"
}



variable "ops_account_ram_users" {
  type        = list(object({
    name                 = string
    description          = string
    enable_console_login = bool
    enable_api_access    = bool
    system_policy        = list(string)
  }))
  description = "RAM users to be created in ops account"
}



variable "dev_account_ram_users" {
  type        = list(object({
    name                 = string
    description          = string
    enable_console_login = bool
    enable_api_access    = bool
    system_policy        = list(string)
  }))
  description = "RAM users to be created in development account"
}



variable "prod_account_ram_users" {
  type        = list(object({
    name                 = string
    description          = string
    enable_console_login = bool
    enable_api_access    = bool
    system_policy        = list(string)
  }))
  description = "RAM users to be created in production account"
}




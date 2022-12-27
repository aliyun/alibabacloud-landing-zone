variable "account_id" {}

variable "ram_users" {
  type    = list(object({
    name                 = string
    description          = string
    enable_console_login = bool
    enable_api_access    = bool
  }))

  default = [
    {
      name        = ""
      description = ""

      enable_console_login = false
      enable_api_access    = false
    }
  ]
}

variable "ram_user_initial_pwd" {}

variable "ram_roles" {}

variable "sso_provider_name" {}
variable "security_account_id" {
  type    = string
  default = ""
}

variable "shared_service_account_id" {
  type    = string
  default = ""
}

variable "dev_account_id" {
  type    = string
  default = ""
}

variable "shared_service_account_alb_id" {
  type    = string
  default = ""
}

variable "dev_account_alb_id" {
  type    = string
  default = ""
}

variable "region" {
  type = string
  default = "cn-shanghai"
}

variable "dev_account_domain_name" {
  type = string
  default = ""
}

variable "shared_service_account_domain_name" {
  type = string
  default = ""
}

variable "waf_instance_spec" {
  type = object({
    big_screen           = string
    exclusive_ip_package = string
    ext_bandwidth        = string
    ext_domain_package   = string
    package_code         = string
    prefessional_service = string
    subscription_type    = string
    period               = number
    waf_log              = string
    log_storage          = string
    log_time             = string
  })

  default = {
    big_screen           = "0"
    exclusive_ip_package = "1"
    ext_bandwidth        = "50"
    ext_domain_package   = "1"
    package_code         = "version_3"
    prefessional_service = "false"
    subscription_type    = "Subscription"
    period               = 1
    waf_log              = "false"
    log_storage          = "3"
    log_time             = "180"
  }
}

variable "waf_domain_config" {
  type = object({
    is_access_product = string
    http2_port      = list(number)
    http_port       = list(number)
    https_port      = list(number)
    http_to_user_ip = string
    https_redirect  = string
    load_balancing  = string
  })

  default = {
    is_access_product = "On"
    http2_port      = [443]
    http_port       = [80]
    https_port      = [443]
    http_to_user_ip = "Off"
    https_redirect  = "Off"
    load_balancing  = "IpHash"
  }
}

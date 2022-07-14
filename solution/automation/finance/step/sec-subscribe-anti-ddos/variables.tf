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

variable "dev_account_domain_name" {
  type    = string
  default = ""
}

variable "shared_service_account_domain_name" {
  type    = string
  default = ""
}

variable "shared_service_account_real_servers" {
  type    = list(string)
  default = []
}

variable "dev_account_real_servers" {
  type    = list(string)
  default = []
}

variable "region" {
  type    = string
  default = "cn-shanghai"
}


variable "ddos_bgp_instance_spec" {
  type = object({
    name              = string
    bandwidth         = string
    base_bandwidth    = string
    service_bandwidth = string
    port_count        = string
    domain_count      = string
    period            = string
    product_type      = string
  })

  default = {
    name              = "createByTerraform"
    bandwidth         = "30"
    base_bandwidth    = "30"
    service_bandwidth = "100"
    port_count        = "50"
    domain_count      = "50"
    period            = "1"
    product_type      = "ddoscoo"
  }
}

variable "ddos_domain_rs_type" {
  type = number
  default = 1
}

variable "ddos_domain_https_ext" {
  type    = string
  default = "{\"Http2\":0,\"Http2https\":0,\"Https2http\":0}"
}

variable "ddos_domain_proxy_types" {
  type = list(object({
    proxy_ports = list(number)
    proxy_type  = string
  }))

  default = [
    {
      proxy_ports = [80]
      proxy_type  = "http"
    }
  ]
}



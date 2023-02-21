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

variable "cfw_instance_spec" {
  type = object({
    payment_type    = string
    spec            = string
    ip_number       = number
    band_width      = number
    cfw_log         = bool
    cfw_log_storage = number
    cfw_service     = bool
    fw_vpc_number   = number
    period          = number
  })

  default = {
    payment_type    = "Subscription"
    spec            = "ultimate_version"
    ip_number       = 400
    band_width      = 200
    cfw_log         = false
    cfw_log_storage = 5000
    cfw_service     = false
    fw_vpc_number   = 5
    period          = 6
  }
}

variable "ddos_domain_rs_type" {
  type    = number
  default = 1
}

variable "ddos_domain_https_ext" {
  type    = string
  default = "{\"Http2\":0,\"Http2https\":0,\"Https2http\":0}"
}

variable "cfw_control_policy" {
  type = list(object({
    application_name = string
    acl_action       = string
    description      = string
    destination_type = string
    destination      = string
    dest_port        = string
    dest_port_type   = string
    direction        = string
    proto            = string
    source           = string
    source_type      = string
    order            = number
  }))

  default = [
    {
      application_name = "HTTP"
      acl_action       = "accept"
      description      = "createdByTerraform"
      destination_type = "net"
      destination      = "0.0.0.0/0"
      dest_port        = "80/80"
      dest_port_type   = "port"
      direction        = "out"
      proto            = "TCP"
      source           = "0.0.0.0/0"
      source_type      = "net"
      order            = 1
    },{
      application_name = "HTTPS"
      acl_action       = "accept"
      description      = "createdByTerraform"
      destination_type = "net"
      destination      = "0.0.0.0/0"
      dest_port        = "443/443"
      dest_port_type   = "port"
      direction        = "out"
      proto            = "TCP"
      source           = "0.0.0.0/0"
      source_type      = "net"
      order            = 2
    }
  ]
}



variable "eip_config" {
  type = list(object({
    payment_type     = string
    period           = number
    eip_address_name = string
    tags             = object({})
  }))

  default = [
    {
      payment_type     = "PayAsYouGo"
      period           = null
      eip_address_name = "eip-dmz"
      tags             = {}
    }
  ]
}

variable "eip_association_instance_id" {
  type        = string
  description = "The ID of the ECS or SLB instance or Nat Gateway or NetworkInterface or HaVip."
  default     = ""
}

variable "create_common_bandwidth_package" {
  type        = bool
  description = "Whether to create a bandwidth package. If the value is true, please fill in the following bandwidth package parameters."
  default     = false
}

variable "common_bandwidth_package_name" {
  type        = string
  description = "The name of the common bandwidth package."
  default     = "default-bandwidth-package"
}

variable "common_bandwidth_package_bandwidth" {
  type        = string
  description = "The bandwidth of the common bandwidth package. Unit: Mbps."
  default     = "5"
}

variable "common_bandwidth_package_internet_charge_type" {
  type        = string
  description = "The billing method of the common bandwidth package."
  default     = "PayByBandwidth"
}







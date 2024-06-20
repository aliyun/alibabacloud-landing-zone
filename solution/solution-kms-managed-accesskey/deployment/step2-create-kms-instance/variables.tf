variable "region" {
  type        = string
  default     = ""
  description = "The ID of the region where the vswitch is located "
}

variable "instance_type" {
  type        = string
  default     = "software"
  description = "KMS Instance commodity type (software/hardware)."
  validation {
    condition = (
      var.instance_type == "software" || var.instance_type == "hardware"
    )
    error_message = "The instance_type should be software or hardware"
  }
}

variable "performance" {
  type        = number
  default     = 1000
  description = "The computation performance level of the KMS instance."
}

variable "key_num" {
  type        = number
  default     = 1000
  description = "Maximum number of stored keys."
}

variable "secret_num" {
  type        = number
  default     = 100
  description = "Maximum number of Secrets."
}

variable "access_num" {
  type        = number
  default     = 1
  description = "The number of managed accesses. The maximum number of VPCs and accounts that can access this KMS instance."
}

variable "log" {
  type        = bool
  default     = true
  description = "Instance Audit Log Switch."
}

variable "log_storage" {
  type        = number
  default     = 1000
  description = "Instance log capacity (GB)."
}

variable "purchase_period" {
  type        = number
  default     = 3
  description = "Purchase cycle, in months."
}

variable "auto_renew" {
  type        = bool
  default     = true
  description = "Automatic renewal."
}

variable "vpc_id" {
  type        = string
  description = "Instance VPC id."
}

variable "zone_ids" {
  type        = list(string)
  description = "Instance zone ids. You should set two zones for the KMS instance. Dual-zone deployment improves service availability and disaster recovery capabilities."
  validation {
    condition = (
      length(var.zone_ids) == 2
    )
    error_message = "The size of zone_ids should be two."
  }
}

variable "vswitch_id" {
  type        = string
  description = "Any vSwitch in the availability zones. The vSwitch must have at least one available IP address."
}

variable "bind_vpcs" {
  type = list(object({
    vpc_id       = string
    vswitch_id   = string
    vpc_owner_id = string
  }))
  default     = []
  description = "Aucillary VPCs used to access this KMS instance."
}

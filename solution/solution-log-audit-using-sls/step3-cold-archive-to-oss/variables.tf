variable "logarchive_central_region" {
  type        = string
  description = "Region of the central sls project"
}

variable "logarchive_account_id" {
  type        = string
  description = "The ID of logarchive account"
}

variable "central_sls_project" {
  type        = string
  description = "The name of logarchive central sls project"
}

variable "oss_bucket_name" {
  type        = string
  description = "The name of oss bucket that cold logarchive."
}

variable "is_oss_bucket_existed" {
  type        = bool
  default     = false
  description = "Does the oss bucket already exist. If it is set to true, please set oss_bucket_name to an existing oss bucket, otherwise a new one will be created."
}

variable "central_sls_logstore_exports" {
  type = list(object({
    logstore_name        = string
    export_name_prefix   = optional(string)
    oss_bucket_directory = optional(string)
    suffix               = optional(string)
    buffer_interval      = optional(number)
    buffer_size          = optional(number)
    compress_type        = optional(string)
    time_zone            = optional(string)
    content_type         = optional(string)
  }))
  description = "The logstores that cold archive to oss. You can config multiple and archive them to different oss bucket directories"
}

variable "logarchive_ram_role_name" {
  type        = string
  description = "The name of ram role that sls will archive logs to oss by assuming this role."
}

variable "is_logarchive_ram_role_existed" {
  type        = bool
  default     = false
  description = "Does the ram role already exist. If it is set to true, please set logarchive_ram_role_name to an existing ram role, otherwise a new one will be created."
}

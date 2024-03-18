variable "delivery_region" {
  type        = string
  description = "Region of the delivery source log"
}

variable "delivery_account_id" {
  type        = string
  description = "The ID of the delivery source account"
}

variable "logarchive_central_region" {
  type        = string
  description = "Region of the central sls project"
}

variable "logarchive_account_id" {
  type        = string
  description = "The ID of logarchive account"
}

variable "delivery_sls_project" {
  type        = string
  description = "The name of delivery source sls project"
}

variable "delivery_sls_logstore" {
  type        = string
  description = "The name of delivery source sls logstore"
}

variable "central_sls_project" {
  type        = string
  description = "The name of logarchive central sls project"
}

variable "central_sls_logstore_name" {
  type        = string
  description = "The name of logarchive central sls logstore."
}

variable "is_central_sls_logstore_existed" {
  type        = bool
  default     = false
  description = "Does the logarchive central sls logstore already exist. If it is set to true, please set central_sls_logstore_name to an existing logstore, otherwise a new one will be created. "
}

variable "delivery_ram_name_prefix" {
  type        = string
  default     = ""
  description = "The name prefix of ram role or ram policy in delivery account. The syntax is Prefix-"
}

variable "logarchive_ram_role_name" {
  type        = string
  description = "The name of ram role in logarchive account. Sls will write logs to central sls logstore by assuming this role."
}

variable "is_logarchive_ram_role_existed" {
  type        = bool
  default     = false
  description = "Does the ram role already exist. If it is set to true, please set logarchive_ram_role_name to an existing ram role, otherwise a new one will be created."
}

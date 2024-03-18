variable "logarchive_central_region" {
  type        = string
  description = "Region of the central sls project"
}

variable "logarchive_account_id" {
  type        = string
  description = "The ID of logarchive account"
}

variable "audit_logs" {
  type        = map(string)
  description = "The logs to be audited"
}

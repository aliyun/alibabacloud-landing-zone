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
  description = "The name of oss bucket that cold logarchive"
}

variable "oss_export_job_ids" {
  type        = list(string)
  description = "The IDs of oss export job that cold archiving log"
}

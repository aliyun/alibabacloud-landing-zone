variable "sls_project_name_for_actiontrail" {
  type = string
  default = ""
  description = "(optional) sls project used for archiving ActionTrail logs. If omitted, ActionTrail logs won't be archived to sls."
}

variable "sls_project_region_for_actiontrail" {
  type = string
  default = ""
  description = "the region of sls project used for archiving ActionTrail logs."
}

variable "oss_bucket_name_for_actiontrail" {
  type = string
  default = ""
  description = "(optional) oss bucket used for archiving ActionTrail logs. If omitted, ActionTrail logs won't be archived to oss."
}

variable "sls_project_name_for_cloud_config" {
  type = string
  default = ""
  description = "(optional) sls project used for archiving Cloud Config logs. If omitted, Cloud Config logs won't be archived to sls."
}

variable "oss_bucket_name_for_cloud_config" {
  type = string
  default = ""
  description = "(optional) oss bucket used for archiving Cloud Config logs. If omitted, Cloud Config logs won't be archived to oss."
}

variable "actiontrail_trail_name" {
  type = string
  default = "LogArchive"
  description = "(optional) trail name in ActionTrail."
}

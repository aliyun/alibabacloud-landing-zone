variable "core_folder_name" {
  type = string
  default = "Core"
  description = "(optional) core folder name"
}

variable "applications_folder_name" {
  type = string
  default = "Applications"
  description = "(optional) applications folder name"
}

variable "shared_services_account_name" {
  type = string
  default = "SharedServices"
  description = "(optional) shared services account name"
}

variable "log_archive_account_name" {
  type = string
  default = "LogArchive"
  description = "(optional) log archive account name"
}

variable "security_account_name" {
  type = string
  default = "SecurityServices"
  description = "(optional) security services account name"
}

variable "billing_account_uid" {
  type = string
  default = ""
  description = "(optional) billing account for all member accounts, default for master account in resource directory"
}
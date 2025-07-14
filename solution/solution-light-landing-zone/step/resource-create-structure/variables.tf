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

variable "payer_account_id" {
  type = string
  default = ""
  description = "(optional) billing account for all member accounts, default for master account in resource directory"
}


variable "ops_display_name" {
  type = string
  default = "OpsService"
  description = "(optional) ops services account name"
}

variable "ops_account_name_prefix" {
  type = string
  default = "OpsService"
  description = "(optional) ops services account name"
}

variable "daily_display_name" {
  type = string
  default = "DailyService"
  description = "(optional) daily services account name"
}

variable "daily_account_name_prefix" {
  type = string
  default = "DailyService"
  description = "(optional) daily services account name"
}

variable "prod_display_name" {
  type = string
  default = "ProdService"
  description = "(optional) prod services account name"
}

variable "prod_account_name_prefix" {
  type = string
  default = "ProdService"
  description = "(optional) prod services account name"
}
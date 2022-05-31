#####################
# Cloud SSO Directory
#####################
variable "create_directory" {
  description = "Controls if cloud sso directory should be created (it affects almost all resources)"
  type        = bool
  default     = false
}

variable "directory_id" {
  description = "The id of an existing cloud sso directory. It will be ignored when `create_directory` is true."
  type        = string
  default     = ""
}

variable "directory_name" {
  description = "The name of a new cloud sso directory."
  type        = string
  default     = ""
}
variable "mfa_authentication_status" {
  description = "The mfa authentication status. Valid values: Enabled or Disabled. Default to Enabled."
  type        = string
  default     = "Enabled"
}
variable "scim_synchronization_status" {
  description = "The scim synchronization status. Valid values: Enabled or Disabled. Default to Disabled."
  type        = string
  default     = "Disabled"
}

#####################
# Resource Manager Account
#####################
variable "create_resource_manager_account" {
  description = "Controls if cloud sso user should be created"
  type        = bool
  default     = false
}

variable "account_id" {
  description = "The id of an existing resource account. It will be ignored when `create_resource_manager_account` is true."
  type        = string
  default     = ""
}

variable "display_name" {
  description = "The name of resource manager account. The length is 2 ~ 50 characters or Chinese characters, which can include Chinese characters, English letters, numbers, underscores (_), dots (.) And dashes (-)."
  type        = string
  default     = ""
}
variable "payer_account_id" {
  description = "Settlement account ID. If the value is empty, the current account will be used for settlement."
  type        = string
  default     = ""
}

#####################
# Resource Manager Folder
#####################
variable "create_resource_manager_folder" {
  description = "Controls if resource manager folder should be created when there is no folder named with `folder_name` value."
  type        = bool
  default     = false
}

variable "parent_folder_id" {
  description = "The ID of the parent folder used to fetch the existing folders or create a new folder."
  type        = string
  default     = ""
}

variable "folder_name" {
  description = "The name used to fetch one existed or create a new folder"
  type        = string
  default     = ""
}

#####################
# Cloud SSO Access Assignment
#####################
variable "assign_access_configuration" {
  description = "Controls if assign access permissions on the account."
  type        = bool
  default     = false
}

variable "cloud_sso_group_name_regex" {
  description = "A name regex used to fetch cloud sso groups and then add new cloud sso user to them automatically"
  type        = string
  default     = ""
}

variable "deprovision_strategy" {
  description = "The deprovision strategy. See https://registry.terraform.io/providers/aliyun/alicloud/latest/docs/resources/cloud_sso_access_assignment#deprovision_strategy"
  type        = string
  default     = "DeprovisionForLastAccessAssignmentOnAccount"
}



variable "region" {
  type        = string
  default     = ""
  description = "The ID of the deployment region"
}

variable "share_services_account_id" {
  type        = string
  description = "The ID of share services account"
}

variable "oss_assume_role" {
  type        = string
  default     = "EcsPatchingAutomationTriggerRole"
  description = "The name of ram role in share service account. OOS will trigger ecs patching automation by assuming this role."
}

variable "oos_cross_account_assume_role" {
  type        = string
  default     = "EcsPatchingAutomationRole"
  description = "The name of role in all target account. OOS will cross-account execute ecs patching by assuming this role."
}

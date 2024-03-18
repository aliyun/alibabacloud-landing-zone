variable "region" {
  type        = string
  default     = ""
  description = "The ID of the deployment region"
}

variable "oss_assume_role" {
  type        = string
  default     = "EcsPatchingAutomationTriggerRole"
  description = "The name of ram role. OOS will trigger automation by assuming this role."
}

variable "oos_cross_account_assume_role" {
  type        = string
  default     = "EcsPatchingAutomationRole"
  description = "The name of ram role in all target accounts. OOS will cross-account execute ecs patching by assuming this role."
}

variable "approverRamUserName" {
  type        = string
  default     = ""
  description = "RAM user allowed for approval. This RAM user needs to have corresponding read and write permissions, and you can directly grant it AliyunOOSFullAccess permissions. This RAM user can approve/deny execute patching."
}

variable "approverWebHookUrl" {
  type        = string
  default     = ""
  description = "When manual approval is enabled, a notification will be sent through this WebHook."
}

variable "patchingWebHookUrl" {
  type        = string
  default     = ""
  description = "When execute patching, a notification will be sent through this WebHook."
}

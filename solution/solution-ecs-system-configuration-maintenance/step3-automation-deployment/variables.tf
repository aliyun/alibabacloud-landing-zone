variable "region" {
  type        = string
  default     = ""
  description = "The ID of the deployment region"
}

variable "oss_assume_role" {
  type        = string
  default     = "EcsCommandRunningAutomationTriggerRole"
  description = "The name of ram role. OOS will trigger automation by assuming this role."
}

variable "oos_cross_account_assume_role" {
  type        = string
  default     = "EcsCommandRunningAutomationRole"
  description = "The name of ram role in all target accounts. OOS will cross-account execute ecs command running by assuming this role."
}

variable "approverRamUserName" {
  type        = string
  default     = ""
  description = "RAM user allowed for approval. This RAM user needs to have corresponding read and write permissions, and you can directly grant it AliyunOOSFullAccess permissions. This RAM user can approve/deny execute command running."
}

variable "approverWebHookUrl" {
  type        = string
  default     = ""
  description = "When manual approval is enabled, a notification will be sent through this WebHook."
}

variable "commandRunningWebHookUrl" {
  type        = string
  default     = ""
  description = "When execute command running, a notification will be sent through this WebHook."
}

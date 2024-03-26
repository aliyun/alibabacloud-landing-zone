variable "enabled_alerts" {
  type = list(string)
}

variable "project_name" {
  type = string
}

variable "log_store" {
  type = string
}

variable "lang" {
  type    = string
  default = "zh-CN"
}

variable "user_id" {
  type        = string
  description = "The ID of the user. The ID must be unique in an Alibaba Cloud account. The ID must be 5 to 60 characters in length, and can contain digits, letters, underscores (_), hyphens (-), and periods (.). It must start with a letter."
}

variable "user_name" {
  type        = string
  description = "The name of the user. The name must be 1 to 20 characters in length, and cannot contain any of the following characters: \\ $ | ~ ? & < > { } ` ' \"."
}

variable "user_email" {
  type        = string
  description = "The email address of the user."
}

variable "user_group_id" {
  type        = string
  description = "The ID of the user group. The ID must be unique. The ID must be 5 to 60 characters in length and can contain digits, letters, underscores (_), hyphens (-), and periods (.). It must start with a letter."
}

variable "user_group_name" {
  type        = string
  description = "The name of the user group. The group name must be 1 to 20 characters in length, and cannot contain the following characters: \\ $ | ~ ? & < > { } ` ' \"."
}

variable "action_policy_id" {
  type        = string
  description = "The ID of the action policy. Make sure that the ID is unique within your Alibaba Cloud account. The ID must be 5 to 60 characters in length and can contain digits, letters, underscores (_), hyphens (-), and periods (.). It must start with a letter."
}

variable "action_policy_name" {
  type        = string
  description = "The name of the action policy. The name must be 1 to 40 characters in length, and cannot contain the following characters: \\ $ | ~ ? & < > { } ` ' \"."
}

variable "notification_period" {
  type        = string
  default     = "any"
  description = "Periods for sending alert notifications. Support any, workday, non_workday, worktime, non_worktime."
}

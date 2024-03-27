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

variable "users" {
  type = list(object({
    id    = string
    name  = string
    email = string
  }))
}

variable "user_group_id" {
  type = string
}

variable "user_group_name" {
  type = string
}

variable "action_policy_id" {
  type = string
}

variable "action_policy_name" {
  type = string
}

variable "notification_period" {
  type    = string
  default = "any"
}

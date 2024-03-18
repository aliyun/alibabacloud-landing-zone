variable "enabled_alerts" {
  type = list(string)
}

variable "project_name" {
  type = string
}

variable "project_region" {
  type = string
}

variable "log_store" {
  type = string
}

variable "lang" {
  type    = string
  default = "zh-CN"
}

variable "name" {
  type = string
  description = "policy name"
}

variable "description" {
  type = string
  description = "policy description"
}

variable "policy_document" {
  type = string
  description = "policy document"
}

variable "target_id" {
  type = string
  description = "target which policy is applied to"
}
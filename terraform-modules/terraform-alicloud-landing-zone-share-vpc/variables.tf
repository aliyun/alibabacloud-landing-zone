variable "shared_unit_name" {
  type        = string
  description = "Shared unit name, used when the shared unit not exists"
}

variable "shared_resource_ids" {
  type        = list(string)
  description = "List of shared resource IDs, usually a list of switch IDs"
}

variable "target_account_ids" {
  type        = list(string)
  description = "List of target member account IDs to share resources"
}


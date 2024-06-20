variable "region" {
  type        = string
  default     = ""
  description = "The ID of the region where the vswitch is located "
}

variable "vswitch_ids" {
  type        = list(string)
  description = "The ids of vswitch need shared."
}

variable "resource_share_name" {
  type        = string
  description = "The name of resource share."
}

variable "resource_share_target_id" {
  type        = string
  description = "The target of resource share. It should be the member account id or folder id or resource directory id in resource directory."
}

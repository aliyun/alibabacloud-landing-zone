variable "region" {
  type        = string
  default     = ""
  description = "The ID of the region where the kms instance is located"
}

variable "kms_instance_id" {
  type        = string
  description = "The id of kms instance"
}

variable "kms_key_id" {
  type        = string
  description = "The id of kms CMK that encrypt the managed accesskey"
}

variable "kms_managed_ram_secret_name" {
  type        = string
  description = "The name of kms ram secret that managed accesskey"
}

variable "kms_instance_policy" {
  type = object({
    name          = string
    description   = optional(string)
    network_rules = optional(list(string))
  })
}

variable "kms_shared_gateway_policy" {
  type = object({
    name          = optional(string)
    description   = optional(string)
    network_rules = optional(list(string))
  })
  default = {}
}

variable "aap_name" {
  type        = string
  description = "Application Access Point name"
}

variable "aap_description" {
  type        = string
  description = "Application Access Point description"
  default     = ""
}

variable "client_key_not_after" {
  type        = string
  description = "The ClientKey expiration time. Example: \"2027-08-10 T08:03:30Z\"."
  default     = ""
}

variable "client_key_not_before" {
  type        = string
  description = "The valid start time of the ClientKey. Example: \"2022-08-10 T08:03:30Z\"."
  default     = ""
}

variable "client_key_password" {
  type        = string
  description = "To enhance security, set a password for the downloaded Client Key,When an application accesses KMS, you must use the ClientKey content and this password to initialize the SDK client. The password must be 8 to 64 characters in length and must contain at least two types of the following characters: digits, uppercase letters, lowercase letters, and ~!@#$%^&*?_-."
}

variable "private_key_data_file" {
  type        = string
  description = "The name of file that can save access key id and access key secret. Strongly suggest you to specified it when you creating access key, otherwise, you wouldn't get its secret ever."
  default     = ""
}

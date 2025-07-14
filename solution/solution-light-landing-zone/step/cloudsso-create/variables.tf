variable "cloudsso_directory_name" {
  description = "The name of a new cloud sso directory."
  type        = string
}

variable "cloudsso_access_lists" {
  type = list(object({
    access_configuration_name = string
    permission_policy_list  = list(object({
      policy_type = string
      policy_document = string
      policy_name = string
    }))
  }))
  description = "The Policy List"
}


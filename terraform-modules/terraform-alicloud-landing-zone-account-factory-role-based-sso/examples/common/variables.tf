variable "metadata_file_path" {
  type = string
  default = ""
  description = "metadata.xml exported from IdP"
}

variable "ram_roles" {
  type = list(object({
    name = string
    description = string
    policies = list(string)
  }))
}

variable "saml_provider_name" {
  type = string
  default = "EnterpriseIdP"
  description = "(optional) IdP name used as SSO"
}

variable "saml_provider_description" {
  type = string
  default = "IdP used for role based SSO"
  description = "(optional) IdP description"
}
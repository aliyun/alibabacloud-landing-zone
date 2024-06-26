variable "shared_service_account_id" {
    type    = string
    default = ""
    description = "UID of the shared service account."
}

variable "log_account_id" {
    type    = string
    default = ""
    description = "UID of the log account."
}

variable "security_account_id" {
    type    = string
    default = ""
    description = "UID of the security account."
}

variable "ops_account_id" {
    type    = string
    default = ""
    description = "UID of the ops account."
}

variable "dev_account_id" {
    type    = string
    default = ""
    description = "UID of the development account."
}

variable "prod_account_id" {
    type    = string
    default = ""
    description = "UID of the production account."
}

variable "sso_provider_name" {
    type    = string
    description = "Provider name for SSO in RAM."
}

variable "encodedsaml_metadata_document" {
    type    = string
    description = "SAML document for SSO in RAM, encoded with base64."
}

variable "sso_provider_description" {
    type = string
    default = ""
    description = "Description of the SSO provider."
}


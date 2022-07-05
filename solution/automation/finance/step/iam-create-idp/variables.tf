variable "shared_service_account_id" {
    type    = string
    default = ""
}

variable "log_account_id" {
    type    = string
    default = ""
}

variable "security_account_id" {
    type    = string
    default = ""
}

variable "ops_account_id" {
    type    = string
    default = ""
}

variable "dev_account_id" {
    type    = string
    default = ""
}

variable "prod_account_id" {
    type    = string
    default = ""
}

variable "sso_provider_name" {}

variable "encodedsaml_metadata_document" {}

variable "sso_provider_description" {
    type = string
    default = ""
}


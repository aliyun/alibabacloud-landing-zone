variable "account_id" {
    type    = string
    default = ""
}

variable "policy_name" {}
variable "policy_document" {
    type = string
}
variable "attach_roles" {
    type = list(string)
}
variable "attach_users" {
    type = list(string)
}
variable "reader_name" {}
variable "reader_policy_type" {}
variable "reader_policy_name" {}

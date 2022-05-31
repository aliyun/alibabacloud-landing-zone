# Modify according to the actual situation

variable "shared_account_id" {
    type = string
    description = "The ID of the account where the shared resource is located"
    default = "1333131609463815"
}

variable "shared_region" {
    type = string
    description = "Region to share resources"
    default = "cn-shanghai"
}

variable "shared_unit_name" {
    type = string
    description = "Shared unit name"
    default = "TF-autotest"
}

variable "shared_resource_ids" {
    type = list(string)
    description = "List of shared resource IDs, usually a list of switch IDs"
    default = ["vsw-uf6add9m10956stjhop2n"]
}

variable "target_account_ids" {
    type = list(string)
    description = "List of target member account IDs to share resources"
    default = ["1584945597797346"]
}

variable "access_key" {}

variable "secret_key" {}

variable "region" {}

variable "ecs_instance_id" {
  description = "the ecs instance id for building the golden image"
}

variable "golden_image_architecture" {
  default = "x86_64"
}

variable "resource_manager_folder_id" {
  description = "the folder id to attach this golden image policy to"
}
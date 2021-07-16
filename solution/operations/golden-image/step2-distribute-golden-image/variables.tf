variable "ecs_instance_id" {
  description = "the ecs instance id for building the golden image"
}

variable "golden_image_architecture" {
  default = "x86_64"
}

variable "golden_image_platform" {
  description = "platform for this golden image. eg, Ubuntu"
}

variable "resource_manager_folder_id" {
  description = "the folder id to attach this golden image policy to"
}
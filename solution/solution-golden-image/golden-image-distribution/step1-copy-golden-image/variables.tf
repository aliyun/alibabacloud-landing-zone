variable "target_region_id" {
  type        = string
  default     = ""
  description = "The ID of the region to which the image is copied"
}

variable "source_image_id" {
  type        = string
  description = "The source image ID."
}

variable "source_region_id" {
  type        = string
  description = "The ID of the region to which the source custom image belongs."
}

variable "image_name" {
  type        = string
  default     = ""
  description = "The image name. By default, the value is the name of the source image."
}

variable "description" {
  type        = string
  default     = ""
  description = "The image description. By default, the value is the description of the source image."
}

variable "region" {
  type = string
  description = "The region of the central log project."
  default = "cn-shanghai"
}
variable "cloudsso_directory_name" {
  type = string
  description = "A regex string to filter results by Directory name. "
}

variable "cloudsso_user" {
  description = "cloudsso user info."
  type = list(object({
    user_name = string
    display_name = string
    email = string
    first_name = string
    last_name = string
  }))
}
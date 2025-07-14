variable "cloudsso_directory_name" {
  type = string
  description = "A regex string to filter results by Directory name. "
}
variable "cloudsso_user" {
  type = list(object({
    user_name = string
    display_name = string
    email = string
    first_name = string
    last_name = string
  }))
  default = [{
    user_name = ""
    display_name = ""
    email = ""
    first_name = ""
    last_name = ""
  }]
}

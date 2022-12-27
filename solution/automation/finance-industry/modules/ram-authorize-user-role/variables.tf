variable "ram_users" {
  type = list(object({
    name          = string
    description   = string
    system_policy = list(string)
  }))
}


variable "ram_roles" {
  type = list(object({
    name        = string
    description = string
    system_policy = list(string)
  }))
}
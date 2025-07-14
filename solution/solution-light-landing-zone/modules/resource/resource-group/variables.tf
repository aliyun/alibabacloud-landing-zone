variable "resource_groups" {
  type = list(object({
    resource_group_name = string
    display_name = string
  }))
  description = "resource groups, each item in list should be group name"
}

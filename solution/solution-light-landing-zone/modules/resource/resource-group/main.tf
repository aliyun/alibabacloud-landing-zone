# Create resource group
resource "alicloud_resource_manager_resource_group" "resource_group" {
  for_each = {
    for group in var.resource_groups : group.resource_group_name => group
  }
  resource_group_name = each.value.resource_group_name
  display_name        = each.value.display_name
}

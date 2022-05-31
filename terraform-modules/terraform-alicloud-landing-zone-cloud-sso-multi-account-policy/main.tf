# The cloud sso directory id is the precondition of other cloud sso resources
# In this module, you can specify an existing directory id or get default directory by datasource or create a new one using this resource
data "alicloud_cloud_sso_directories" "default" {}
resource "alicloud_cloud_sso_directory" "this" {
  count                       = var.create_directory ? 1 : 0
  directory_name              = var.directory_name
  mfa_authentication_status   = var.mfa_authentication_status
  scim_synchronization_status = var.scim_synchronization_status
}

# Fetch the existing resource manager folders
data "alicloud_resource_manager_folders" "this" {
  parent_folder_id = var.parent_folder_id
  name_regex       = var.folder_name
}

# Create a new resource manager folder when there is no folder named with `folder_name` value
resource "alicloud_resource_manager_folder" "this" {
  count            = var.folder_name == "" ? 0 : var.create_resource_manager_folder ? 1 : 0
  parent_folder_id = var.parent_folder_id
  folder_name      = var.folder_name
}

# Create a resource manager account
resource "alicloud_resource_manager_account" "this" {
  count            = var.create_resource_manager_account ? 1 : 0
  display_name     = var.display_name
  folder_id        = local.this_folder_id
  payer_account_id = var.payer_account_id
}

# Filter the cloud sso group and then used to configure the resource manager account
# By default, the filtered group name matches with  <Self-define Prefix>-<Resource Manager Account Name>-<Cloud SSO Access Configuration Name>, like "ALIYUN-accountName-acName"
# Also, the filter condition can be self-define by variable `user_group_name_regex`
data "alicloud_cloud_sso_groups" "this" {
  directory_id = local.this_directory_id
  name_regex   = local.group_name_regex
}

# Filter the cloud sso access configuration and then used to configure the resource manager account
# At first, fetch all of configurations and then use cloud sso name suffix to filter them.
data "alicloud_cloud_sso_access_configurations" "this" {
  directory_id = local.this_directory_id
}

# Using the cloud sso group and access configurations and configure the resource account
resource "alicloud_cloud_sso_access_assignment" "default" {
  count                   = var.assign_access_configuration ? length(local.matched_access_configurations) : 0
  directory_id            = local.this_directory_id
  access_configuration_id = local.matched_access_configurations[count.index].access_configuration_id
  target_type             = "RD-Account"
  target_id               = local.this_account_id
  principal_type          = "Group"
  principal_id            = [for group in local.matched_groups : group.group_id if length(regexall(format(".*%s.*", local.matched_access_configurations[count.index].access_configuration_name), group.group_name)) > 0][0]
  deprovision_strategy    = var.deprovision_strategy
}


provider "alicloud" {
  configuration_source = "AlibabaCloud/LightLandingZone 1.0"
}

locals {
  directory_name                   = var.cloudsso_directory_name
  permission_policies                   = var.cloudsso_access_lists
}


module "create-cloudsso-and-access-configuration" {
  source               = "../../modules/create-cloudsso-and-access"
  directory_name = local.directory_name
  permission_policies = local.permission_policies
}


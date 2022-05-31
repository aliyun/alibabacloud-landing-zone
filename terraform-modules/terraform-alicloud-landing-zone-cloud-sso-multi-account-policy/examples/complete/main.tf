provider "alicloud" {
  region = "cn-shanghai"
}
data "terraform_remote_state" "local" {
  backend = "local"

  config = {
    path = "terraform.tfstate"
  }
}

locals {
  last_create_directory               = lookup(data.terraform_remote_state.local.outputs, "create_directory", false)
  last_create_resource_manager_folder = lookup(data.terraform_remote_state.local.outputs, "create_resource_manager_folder", false)
}

// Step1: create a new directory and a new folder

# Fetch the existing resource manager folders and directories
data "alicloud_cloud_sso_directories" "this" {}
data "alicloud_resource_manager_folders" "this" {
  name_regex = var.folder_name
}
module "directory" {
  source                      = "../../"
  create_directory            = local.last_create_directory ? true : length(data.alicloud_cloud_sso_directories.this.ids) > 0 ? false : var.create_directory
  directory_name              = var.directory_name
  mfa_authentication_status   = var.mfa_authentication_status
  scim_synchronization_status = var.scim_synchronization_status

  create_resource_manager_folder = local.last_create_resource_manager_folder ? true : length(data.alicloud_resource_manager_folders.this.ids) > 0 ? false : var.create_resource_manager_folder
  folder_name                    = var.folder_name
}

// Step2: create two groups and access configurations using existing directory
module "group1" {
  source           = "terraform-alicloud-modules/cloud-sso/alicloud"
  create_directory = false
  directory_id     = module.directory.directory_id

  create_group = var.create_group
  group_name   = format("ALIYUN-%s-acModule-01", var.display_name)

  create_access_configuration = var.create_access_configuration
  access_configurations = [
    {
      access_configuration_name = "acModule-01"
      description               = "acModule-01"
      permission_policies = [
        {
          permission_policy_document = <<EOF
            {
              "Statement":[
                {
                  "Action":"ecs:Get*",
                  "Effect":"Allow",
                  "Resource":[
                    "*"
                  ]
                }
              ],
              "Version": "1"
            }
          EOF
          permission_policy_type     = "Inline"
          permission_policy_name     = "acModule-01-ecs-policy"
        }
      ]
      relay_state                      = "https://cloudsso.console.aliyun.com/test1"
      session_duration                 = 1800
      force_remove_permission_policies = true
    }
  ]
}

module "group2" {
  source           = "terraform-alicloud-modules/cloud-sso/alicloud"
  create_directory = false
  directory_id     = module.directory.directory_id

  create_group                = var.create_group
  group_name                  = format("ALIYUN-%s-acModule-02", var.display_name)
  create_access_configuration = var.create_access_configuration
  access_configurations = [
    {
      access_configuration_name = "acModule-02"
      description               = "acModule-02"
      permission_policies = [
        {
          permission_policy_document = <<EOF
            {
              "Statement":[
                {
                  "Action":"ecs:Get*",
                  "Effect":"Allow",
                  "Resource":[
                    "*"
                  ]
                }
              ],
              "Version": "1"
            }
          EOF
          permission_policy_type     = "Inline"
          permission_policy_name     = "acModule-02-ecs-policy"
        }
      ]
      relay_state                      = "https://cloudsso.console.aliyun.com/test1"
      session_duration                 = 1800
      force_remove_permission_policies = true
    }
  ]
}

data "alicloud_account" "this" {}
// Step3: create a new RD account and assign the cloud sso policy
module "multi-account" {
  source           = "../../"
  create_directory = false

  create_resource_manager_folder = false
  folder_name                    = module.directory.folder_name

  create_resource_manager_account = var.create_resource_manager_account
  display_name                    = var.display_name
  payer_account_id                = var.parent_folder_id

  assign_access_configuration = var.assign_access_configuration
  cloud_sso_group_name_regex  = format("ALIYUN-%s", var.display_name)
}
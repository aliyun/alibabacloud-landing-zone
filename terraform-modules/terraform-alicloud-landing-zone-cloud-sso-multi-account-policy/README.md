# terraform-alicloud-landing-zone-cloud-sso-multi-account-policy

Terraform module which creates RD account and manage it policy by cloud sso on Alibaba Cloud.

## Usage

```terraform
module "cloud_sso" {
  source = "terraform-alicloud-modules/landing-zone-cloud-sso-multi-account-policy/alicloud"

  create_directory                = false
  directory_id                    = data.alicloud_cloud_sso_directories.default.ids.0
  create_resource_manager_account = true
  display_name                    = "xxappnamedev"
  
  create_resource_manager_folder = true
  folder_name                    = "appname"

  assign_access_configuration    = true
}
```

## Resource Manager Folder

Resource manager account can be added into one folder while creating. This module support to get an existing folder by 
specifying the following parameters:

```terraform
parent_folder_id = "rd-xxxxx"
folder_name      = "appname"
```

Or, create a new folder by specifying the following parameters and add the resource manager account into this one:

```terraform
create_resource_manager_folder = true
parent_folder_id               = "rd-xxxxx"
folder_name                    = "appname"
```

## Resource Manager Account

This module support to specify an existing resource manager account by specifying the following parameters:

```terraform
create_resource_manager_account = false
account_id                      = "rd-xxxxx"
```

Or, create a new account by specifying the following parameters:

```terraform
create_resource_manager_account = true
display_name                    = var.display_name
payer_account_id                = var.payer_account_id
```

## Cloud SSO User Group And Access Configurations

In the cloud sso multi-account policy management scenario, each account needs in turn to set user group and access configuration.
In order to make multi-account management more inconvenient, the policy configuration follows the following rules:

1. the cloud sso group name must format as `<User Group Prefix>-<Resource Manager Account Name>-<Cloud SSO Access Configuration Name>`,
   like "ALIYUN-foo-acName", "ALIYUN-foo-acName1", "ALIYUN-bar-acName"
2. this module will filter the groups by `cloud_sso_group_name_regex` and if its value is empty, the default value `".*<Resource Manager Account Name>"`
3. if there are matched groups, this module will add the resource manager account into them, like account "foo" will be added into "ALIYUN-foo-acName" and "ALIYUN-foo-acName1"
4. also, this module will split all matched group names by `<Resource Manager Account Name>-` and then filter the access configurations by the second parts of the split,
   like "ALIYUN-foo-acName" will be split to "ALIYUN-" and "acName", and then this module will filter the access configurations by "acName"
5. if there are matched access configurations and set `assign_access_configuration = true`, this module will add the access configuration into the matched groups,
   like "acName" will be added into the group "ALIYUN-foo-acName"

The cloud sso directory id is the precondition of all cloud sso resources, so there should specify a directory:

```terraform
directory_id = "rd-ywxxxxx"
```

If the `directory_id` value is empty, this module will fetch the current account default directory automatically.

Also, this module supports to create a new cloud sso directory by the following parameters:

```terraform
create_directory            = true
directory_name              = "my-directory"
mfa_authentication_status   = "Enabled"
scim_synchronization_status = "Enabled"
```

## Examples

- [Complete](https://github.com/terraform-alicloud-modules/terraform-alicloud-cloud-sso-multi-account-policy/tree/master/examples/complete)

## Contributing

Report issues/questions/feature requests on in the [issues](https://github.com/terraform-alicloud-modules/terraform-alicloud-cloud-sso-multi-account-policy/issues/new) section.

<!-- BEGINNING OF PRE-COMMIT-TERRAFORM DOCS HOOK -->
## Requirements

| Name | Version |
|------|---------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | >= 0.13.1 |
| <a name="requirement_alicloud"></a> [alicloud](#requirement\_alicloud) | >= 1.145.0 |

## Providers

| Name | Version |
|------|---------|
| <a name="provider_alicloud"></a> [alicloud](#provider\_alicloud) | >= 1.145.0

## Docs

This module has been published in terraform registry. See [Docs](https://registry.terraform.io/modules/terraform-alicloud-modules/cloud-sso-multi-account-policy/alicloud/latest)

## Authors

Created and maintained by Alibaba Cloud Terraform Team(terraform@alibabacloud.com)

## License

MIT License. See LICENSE for full details.

## Reference

* [Terraform-Provider-Alicloud Github](https://github.com/aliyun/terraform-provider-alicloud)
* [Terraform-Provider-Alicloud Release](https://releases.hashicorp.com/terraform-provider-alicloud/)
* [Terraform-Provider-Alicloud Docs](https://registry.terraform.io/providers/aliyun/alicloud/latest/docs)


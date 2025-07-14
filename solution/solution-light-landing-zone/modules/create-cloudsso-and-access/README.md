# Overview

Provides a CloudSSO And Initialize several user groups,And assign simple permissions to user groups.

## Usage

```terraform
module "create-cloudsso-and-group" {
  source                        = "../../../.."
  group_list                    = var.group_list
  directory_name                = var.directory_name
  mfa_authentication_status     = var.mfa_authentication_status
  scim_synchronization_status   = var.scim_synchronization_status
  access_configuration_name     = var.access_configuration_name
  target_account_list           = var.target_account_list
  permission_policies           = var.permission_policies
  relay_state                   = var.relay_state
  session_duration              = var.session_duration
}
```

## Examples
- Please see the example code inside the examples folder.

## Contributing

Report issues/questions/feature requests on in the [issues](https://github.com/aliyun/alibabacloud-landing-zone/issues) section.

<!-- BEGINNING OF PRE-COMMIT-TERRAFORM DOCS HOOK -->
## Requirements

| Name                                                                            | Version     |
|---------------------------------------------------------------------------------|-------------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform)       | \>= 1.1.7   |
| <a name="requirement_alicloud"></a> [alicloud provider](#requirement\_alicloud) | \>= 1.194.0 |

## Docs

Please see the example above.

## Authors

Created and maintained by Alibaba Cloud Landing Zone Team.

## License

MIT License. See LICENSE for full details.

## Reference

* [Terraform-Provider-Alicloud Github](https://github.com/aliyun/terraform-provider-alicloud)
* [Terraform-Provider-Alicloud Release](https://releases.hashicorp.com/terraform-provider-alicloud/)
* [Terraform-Provider-Alicloud Docs](https://registry.terraform.io/providers/aliyun/alicloud/latest/docs)

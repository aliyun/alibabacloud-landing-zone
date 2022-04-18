# Share VPC

This module is used to share switches in a VPC among the member accounts of resource directory.

## Usage

```terraform
module "resource_share" {
  source = "terraform-modules/terraform-alicloud-landing-zone-share-vpc"
  shared_unit_name = "yourSharedUnitName"
  shared_resource_ids = ["vsw-uf6xwxxxx"]
  target_account_ids = ["1024102410241024"]
}
```

## Examples

- [Sharing switches in a VPC among member accounts](https://github.com/aliyun/alibabacloud-landing-zone/tree/master/terraform-modules/terraform-alicloud-landing-zone-share-vpc/examples/complete)

## Contributing

Report issues/questions/feature requests on in the [issues](https://github.com/aliyun/alibabacloud-landing-zone/issues) section.

<!-- BEGINNING OF PRE-COMMIT-TERRAFORM DOCS HOOK -->
## Requirements

| Name | Version    |
|------|------------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | >= 0.12    |
| <a name="requirement_alicloud"></a> [alicloud](#requirement\_alicloud) | >= 1.111.0 |

## Docs

Please see the example above.

## Authors

Created and maintained by Alibaba Cloud Landing Zone Team

## License

MIT License. See LICENSE for full details.

## Reference

* [Terraform-Provider-Alicloud Github](https://github.com/aliyun/terraform-provider-alicloud)
* [Terraform-Provider-Alicloud Release](https://releases.hashicorp.com/terraform-provider-alicloud/)
* [Terraform-Provider-Alicloud Docs](https://registry.terraform.io/providers/aliyun/alicloud/latest/docs)

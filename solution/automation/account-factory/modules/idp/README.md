# terraform-alicloud-idp

This Module is used to create an IDP in the RAM of the Alibaba Cloud target account, which is used for SSO between enterprise customers and Alibaba Cloud.

## Usage

```terraform
module "idp" {
  source = "modules/idp"
  sso_provider_name = "yourProviderName"
  encodedsaml_metadata_document = "yourEncodedSAMLMetadataDocument"
}
```

## Examples

- [Create an IDP in a member account through an management master account](https://github.com/aliyun/alibabacloud-landing-zone/tree/master/solution/automation/account-factory/modules/idp/examples/complete)

## Contributing

Report issues/questions/feature requests on in the [issues](https://github.com/aliyun/alibabacloud-landing-zone/issues) section.

<!-- BEGINNING OF PRE-COMMIT-TERRAFORM DOCS HOOK -->
## Requirements

| Name | Version    |
|------|------------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | >= 0.12    |
| <a name="requirement_alicloud"></a> [alicloud](#requirement\_alicloud) | >= 1.127.0 |

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

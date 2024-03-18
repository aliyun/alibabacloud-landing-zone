# Overview

This module can be used to create a nat gateway, bind with EIP, add SNAT entries.

## Usage

```terraform
module "dmz_egress_nat_gateway" {
  source                  = "terraform-modules/terraform-alicloud-landing-zone-nat-gateway"
  vpc_id                  = "vpc-xxx"
  name                    = "dmz-nat-gateway"
  vswitch_id              = "vsw-xxx"
  snat_source_cidr_list   = ["192.168.0.1"]
  association_eip_id_list = ["eip-xxx"]
  snat_ip_list            = ["112.142.11.23"]
}
```

## Examples

- Please see the example code inside the examples folder.

## Contributing

Report issues/questions/feature requests on in the [issues](https://github.com/aliyun/alibabacloud-landing-zone/issues)
section.

<!-- BEGINNING OF PRE-COMMIT-TERRAFORM DOCS HOOK -->

## Requirements

| Name                                                                            | Version     |
|---------------------------------------------------------------------------------|-------------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform)       | \>= 1.1.7   |
| <a name="requirement_alicloud"></a> [alicloud provider](#requirement\_alicloud) | \>= 1.160.0 |

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

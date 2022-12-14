# Overview

This module can be used to create EIP, bind with bandwidth package or bind with other cloud resources.

## Usage

```terraform
module "dmz_egress_eip" {
  source    = "terraform-modules/terraform-alicloud-landing-zone-eip"
  eip_config                                    = [
    {
      payment_type     = "PayAsYouGo"
      eip_address_name = "eip-dmz"
      period           = null
      tags             = {
        "Environment" = "shared"
        "Department"  = "ops"
      }
    }
  ]
  create_common_bandwidth_package               = true
  common_bandwidth_package_bandwidth            = 5
  common_bandwidth_package_internet_charge_type = "PayByBandwidth"
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

# Share VPC

This module can be used to create a routing table, bind with vSwitch, add routing entries in VPC routing table.

## Usage

```terraform
module "dmz_egress_biz_vpc_route" {
  source             = "terraform-modules/terraform-alicloud-landing-zone-vpc-custom-route"
  vpc_id             = "vpc-xxx"
  create_route_table = false
  route_entry_config = [
    {
      name                  = "dmz-egress"
      destination_cidrblock = "0.0.0.0/0"
      nexthop_type          = "Attachment"
      nexthop_id            = "tr-attach-xxx"
    }
  ]
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

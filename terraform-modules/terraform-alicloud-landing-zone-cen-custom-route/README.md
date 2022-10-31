# Share VPC

This module can be used to create a routing table， add routing table association， add routing entries in Cloud Enterprise Network.

## Usage

```terraform
module "dmz_egress_tr_route" {
  source    = "terraform-modules/terraform-alicloud-landing-zone-cen-custom-route"
  create_route_table                = false
  transit_router_id                 = "xxx"
  transit_router_route_entry_config = [
    {
      route_entry_dest_cidr     = "0.0.0.0/0"
      route_entry_next_hop_type = "Attachment"
      route_entry_name          = "default-to-dmz"
      route_entry_description   = "default-to-dmz"
      route_entry_next_hop_id   = "xxx"
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

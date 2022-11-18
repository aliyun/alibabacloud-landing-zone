# Share VPC

Provides a CEN transit router VPC attachment resource that associate the VPC with the CEN instance. For example, it can
be used to load VPC instances across accounts in CEN, between shared service account and business account.

## Usage

```terraform
module "cen_attach" {
  source    = "terraform-modules/terraform-alicloud-landing-zone-cen-vpc-attach"
  providers = {
    alicloud.shared_service_account = alicloud.shared_service_account
    alicloud.vpc_account            = alicloud.vpc_account
  }

  shared_service_account_id = var.shared_service_account_id
  vpc_account_id            = var.vpc_account_id
  cen_instance_id           = var.cen_instance_id
  cen_transit_router_id     = var.cen_transit_router_id
  vpc_id                    = var.vpc_id

  primary_vswitch = {
    vswitch_id = var.primary_vswitch.vswitch_id,
    zone_id    = var.primary_vswitch.zone_id
  }

  secondary_vswitch = {
    vswitch_id = var.secondary_vswitch.vswitch_id,
    zone_id    = var.secondary_vswitch.zone_id
  }

  transit_router_attachment_name = var.transit_router_attachment_name
  transit_router_attachment_desc = var.transit_router_attachment_desc

  route_table_association_enabled = var.route_table_association_enabled
  route_table_propagation_enabled = var.route_table_propagation_enabled
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

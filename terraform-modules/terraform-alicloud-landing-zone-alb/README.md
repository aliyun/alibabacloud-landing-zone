# Share VPC

This module can be used to create Application Load Balancer.

## Usage

```terraform
module "dmz_ingress_alb" {
  source                       = "terraform-modules/terraform-alicloud-landing-zone-alb"
  vpc_id                       = var.vpc_id
  alb_instance_deploy_config   = {
    load_balancer_name = "alb-dmz-ingress"
    zone_1_id          = "cn-shanghai-f"
    vswitch_1_id       = "vsw-xxx"

    zone_2_id    = "cn-shanghai-g"
    vswitch_2_id = "vsw-xxx"
  }
  server_group_backend_servers = []
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

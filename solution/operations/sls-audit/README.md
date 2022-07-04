# 云产品及主机系统日志统一投递方案

在多账号场景下，客户不同云账号内主机层面操作系统日志及云产品日志希望统一投递到日志账号。便于运维的集中式管理。本方案会介绍如何使用阿里云日志服务产品来配置多账号的云产品日志审计及主机系统日志。
## 操作步骤

```bash
terraform init
terraform plan -var-file=default.tfvars
terraform apply -var-file=default.tfvars
```

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

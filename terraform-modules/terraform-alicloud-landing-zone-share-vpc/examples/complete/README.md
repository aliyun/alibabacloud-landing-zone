# Complete
This example will share switches in a VPC with other member accounts of resource directory.

By assuming the role of resource directory, the RAM user in master account will create a shared unit in the member account, 
then share the switches with target member account in the shared unit.

## Usage
To run this example you need to execute:

```bash
$ terraform init
$ terraform plan
$ terraform apply
```

This example can be used in pipeline scenario. You can run this example as the following commands:

```bash
$ terraform init \
    -backend-config="key=xxx" \
    -backend-config="bucket=xxx" \
    -backend-config="prefix=xxx" \
    -backend-config="region=cn-hangzhou" \
    -backend-config="endpoint=oss-cn-hangzhou.aliyuncs.com" \
    -backend-config="tablestore_endpoint=https://xxx.cn-hangzhou.ots.aliyuncs.com" \
    -backend-config="tablestore_table=xxx"
    
$ terraform plan \
    -var "shared_account_id=xxx" \
    -var "shared_region=xxx"  \
    -var "shared_unit_name=xxx" \
    -var 'shared_resource_ids=["xxx"]' \
    -var 'target_account_ids=["xxx"]'
    
$ terraform apply
    -var "shared_account_id=xxx" \
    -var "shared_region=xxx"  \
    -var "shared_unit_name=xxx" \
    -var 'shared_resource_ids=["xxx"]' \
    -var 'target_account_ids=["xxx"]'
```

Also, you can add more scenarios in the folder `examples`.

<!-- BEGINNING OF PRE-COMMIT-TERRAFORM DOCS HOOK -->
## Requirements

| Name | Version   |
|------|-----------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | >= 0.12   |
| <a name="requirement_alicloud"></a> [alicloud](#requirement\_alicloud) | > 1.111.0 |

## Providers

| Name | Version   |
|------|-----------|
| <a name="provider_alicloud"></a> [alicloud](#provider\_alicloud) | > 1.111.0 |


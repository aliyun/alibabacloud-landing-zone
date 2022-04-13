# Scenario-other-1
This example is the same as the complete scene, just an sample of distinguishing scenes by directory.

This example will create an IDP in the target member account.

By assuming the role of the resource directory, the RAM user in master account will create an IDP in the target member account.

## Usage
To run this example you need to execute:

```bash
$ terraform init
$ terraform plan
$ terraform apply
```

This example is used in the pipeline scenario. You can run this example as the following commands:

### Step 1: Create an IDP

```bash
$ terraform init \
    -backend-config="key=xxx" \
    -backend-config="bucket=xxx" \
    -backend-config="prefix=xxx" \
    -backend-config="region=cn-hangzhou" \
    -backend-config="endpoint=oss-cn-hangzhou.aliyuncs.com" \
    -backend-config="tablestore_endpoint=https://xxx.cn-hangzhou.ots.aliyuncs.com" \
    -backend-config="tablestore_table=xxx"
$ terraform plan -var "account_id=xxx" -var "sso_provider_name=xxx"  -var "encodedsaml_metadata_document=xxx"
$ terraform apply -var "account_id=xxx" -var "sso_provider_name=xxx"  -var "encodedsaml_metadata_document=xxx"
```

Also, you can add more scenarios in the folder `examples`.

<!-- BEGINNING OF PRE-COMMIT-TERRAFORM DOCS HOOK -->
## Requirements

| Name | Version    |
|------|------------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | >= 0.12    |
| <a name="requirement_alicloud"></a> [alicloud](#requirement\_alicloud) | >= 1.127.0 |

## Providers

| Name | Version    |
|------|------------|
| <a name="provider_alicloud"></a> [alicloud](#provider\_alicloud) | >= 1.127.0 |


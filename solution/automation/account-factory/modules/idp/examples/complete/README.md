# Complete
This example will create an IDP in the target member account.

By assuming the role of the resource directory, the RAM user in master account will create an IDP in the target member account.

## Usage
To run this example you need to execute:

```bash
$ terraform init
$ terraform plan
$ terraform apply
```

This example is used in the pipeline scenario, so it provides the tf variables file in the folder `tfvars`.
You can run this example as the following commands:

### Step 1: Create an IDP

```bash
$ terraform init -backend-config=tfvars/backend.tfvars
$ terraform plan -var-file=tfvars/step-01-create-idp.tfvars
$ terraform apply --auto-approve -var-file=tfvars/step-01-create-idp.tfvars
```

Also, you can add more variables files in the folder `tfvars`.

<!-- BEGINNING OF PRE-COMMIT-TERRAFORM DOCS HOOK -->
## Requirements

| Name | Version    |
|------|------------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | >= 0.12    |
| <a name="requirement_alicloud"></a> [alicloud](#requirement\_alicloud) | >= 1.145.0 |

## Providers

| Name | Version |
|------|---------|
| <a name="provider_alicloud"></a> [alicloud](#provider\_alicloud) | >= 1.145.0 |
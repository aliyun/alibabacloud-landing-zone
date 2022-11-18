# Complete
This example will create a CEN transit router VPC attachment resource that associate the VPC with the CEN instance..
For example, it can be used to load VPC instances across accounts in CEN, between shared service account and business account.

## Usage
To run this example you need to execute:

```bash
$ terraform init
$ terraform plan --var-file=tfvars/settings.tfvars
$ terraform apply --var-file=tfvars/settings.tfvars
```

Also, you can add more scenarios in the folder `examples`.

<!-- BEGINNING OF PRE-COMMIT-TERRAFORM DOCS HOOK -->
## Requirements

| Name | Version     |
|------|-------------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | \>= 1.1.7   |
| <a name="requirement_alicloud"></a> [alicloud](#requirement\_alicloud) | \>= 1.160.0 |

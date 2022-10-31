# Complete
This example will create an Application Load Balancer for public network.
For example, it can be used to build public network entrance.

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

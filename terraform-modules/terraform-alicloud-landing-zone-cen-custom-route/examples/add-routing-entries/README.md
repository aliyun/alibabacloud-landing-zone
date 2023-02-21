# Complete
This example will add routing entries in CEN routing table.
For example, it can be used to add back-to-source routing for ALB when building public network entrance.

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

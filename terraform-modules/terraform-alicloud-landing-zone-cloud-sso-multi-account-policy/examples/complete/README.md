# Complete

Configuration in this directory creates set of Cloud SSO and Resource Manager resources which may be sufficient for staging or production environment.

There are cloud sso directory, resource manager folder, resource manager account created, and assign cloud sso access configurations to resource manager account.

## Usage

To run this example you need to execute:

```bash
$ terraform init
$ terraform plan
$ terraform apply
```

This example is used in the pipeline scenario, so it provides the tf variables file in the folder `tfvars`. 
You can run this example as the following commands:

### Step 1: Creating a cloud sso directory and resource manager folder

```bash
$ terraform plan -var-file=tfvars/step-01.tfvars
$ terraform apply -var-file=tfvars/step-01.tfvars
```

### Step2: create two groups and access configurations using existing directory

```bash
$ terraform plan -var-file=tfvars/step-02.tfvars
$ terraform apply -var-file=tfvars/step-02.tfvars
```

### Step3: create a new RD account and assign the cloud sso policy

```bash
$ terraform plan -var-file=tfvars/step-03.tfvars
$ terraform apply -var-file=tfvars/step-03.tfvars
```

Also, you can add more variables files in the folder `tfvars`.

<!-- BEGINNING OF PRE-COMMIT-TERRAFORM DOCS HOOK -->
## Requirements

| Name | Version |
|------|---------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | >= 0.13.1 |
| <a name="requirement_alicloud"></a> [alicloud](#requirement\_alicloud) | >= 1.145.0 |

## Providers

| Name | Version |
|------|---------|
| <a name="provider_alicloud"></a> [alicloud](#provider\_alicloud) | >= 1.145.0 |

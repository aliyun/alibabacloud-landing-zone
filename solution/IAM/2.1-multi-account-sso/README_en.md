# Configuration of multi-account SSO automation

[中文](./README.md)｜English

## Solution overview

This solution uses Terraform to implement automation. Due to the limits of Terraform, for example, dynamic providers are not supported, this solution is implemented in two steps. The first step (Step 1) is to dynamically generate the Terraform template required in the second step (Step 2) based on the member account list in the resource directory of the Alibaba Cloud account and automatically set the parameter values. The second step is to create IdPs and RAM roles in the member accounts based on the Terraform template. All configuration items are completed in Step 1. 

## Prerequisites

-	Terraform is installed in the current environment.
-	The resource directory service is enabled for the management account.
-	You have obtained the AccessKeys of RAM users in the management account, and the users have permissions to perform the operations, including AliyunResourceDirectoryReadOnlyAccess, AliyunSTSAssumeRoleAccess, and AliyunRAMFullAccess. 
-	You have obtained the Idp meta.xml metadata file.

## Procedure

1.	Download the code package in the attachment and decompress it to a directory. The directory structure is as follows:
```
├── modules             // Module directory. Do not modify it.
│ ├── role // The operations to create a role are encapsulated as a module, which is easy to use. Do not modify it.
│       ├── main.tf 
│       └── variables.tf
└── step1               // The directory required in Step 1.
    ├── main.tf         // The entry file used in Step 1. Do not modify it..
    ├── settings.tfvars // Configure file. You can modify it as required.
    ├── step2.tmpl      // Used to generate the template file for operations in Step 2. Do not modify it.
    └── variables.tf    // The definitions of variables used in Step 1. Do not modify them.
```
2.	Open the `step1/settings.tfvars` file and modify configuration items in the file based on the comments:
  -	Set `access_key` and `secret_key` respectively to the values of AccessKey ID and AccessKey secret in prerequisites.
  -	Modify the `ram_roles` list based on your needs. After you run the script, the roles defined are automatically created and granted corresponding permissions. 
```
# Role list
ram_roles = {
  "ssoTestRole": {
    description = "Test for Terraform"
    policies = [
      "AliyunLogFullAccess"
    ]
  }
}
```
  -	Modify IdP configuration information based on your needs, where `metadata` stores the path of the Idp xml metadata file.
```
# IdP name
saml_provider_name = "tf-testIdp"

# The path of the IdP metadata xml file
metadata = "./meta.xml"
```
  -	Enter the UIDs of RAM users for which no IdP or RAM role is created based on your needs. 
```
# The RAM user list. Enter the UIDs of RAM users for which no IdP or RAM role is created. 
exclude = ["113************"]
# To apply this solution to all RAM users, set exclude to []. 
# exclude = []
```
3.	Perform the following operations in Step 1:
  -	Go to the `step1` directory and run the `terraform init` command.
  -	Run the `terraform plan -var-file=settings.tfvars` command and check whether any error occurs. If an error occurs, check whether the configuration items are valid in Step 2.
  -	Run the `terraform apply -var-file=settings.tfvars -parallelism=1` command. Enter yes after the self-check succeeds. When the command is executed successfully, a directory containing the `main.tf` file is generated in the root directory called `step2`.
  ![22.多账号sso-step1-apply](../img/22.多账号sso-step1-apply.png)
4.	Perform the following operations in Step 2:
  -	Go to `step2` directory and run the `terraform init` command.
  -	Run the `terraform plan` command and check whether any error occurs.
  -	Run the `terraform apply` command. Enter yes after the self-check succeeds. When the command is executed successfully, the role arn and idp arn created in each member account is displayed in the `role-arn,idp-arn` format in the console.
  ![23.多账号sso-step2-apply](../img/23.多账号sso-step2-apply.png) 

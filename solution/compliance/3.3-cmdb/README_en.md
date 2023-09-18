# Synchronization of cloud configurations to the CMDB of an enterprise

[中文](./README.md)｜English

## Solution overview

This solution uses Terraform automation. You can add the management account and all its member accounts to the global account group and select one account as the log archive account based on your needs. You can create a log service project and a Logstore to store CMDB data within the log archive account, and configure to deliver data to this project and this Logstore within the management account. After the script is successfully executed, you can view configuration changes on the Consumption Preview page for the corresponding Logstore in the Log Service console. 

## Prerequisites

-	Terraform has been installed in the current environment. 
-	The resource directory service is enabled for the management account. 
-	You have obtained the AccessKeys of RAM users in the management account, and the users have permissions to perform the operations, including `AliyunResourceDirectoryReadOnlyAccess`, `AliyunSTSAssumeRoleAccess`, and `AliyunRAMFullAccess`. 

## Procedure
1.	Download the code package and decompress it to a directory. The directory structure is as follows:
```
    ├── main.tf         // Entry file. Do not modify it.
    ├── settings.tfvars // Configure file. You can modify it as required.
    ├── README.md       // Operation document. Do not modify it.
    └── variables.tf    // The definitions of variables used. Do not modify it.
```
2.	Open the `step1/settings.tfvars` file and modify configuration items in the file based on the comments:
  -	Set `master_account_access_key` and `master_account_secret_key` respectively to the values of AccessKey ID and AccessKey secret of the Alibaba Cloud account.
  -	Select a member account in the resource directory of the Alibaba Cloud account as the log archive account, and enter its UID in `cmdb_account_uid`.
  -	Specify the names of the project and the Logstore used to store CMDB data. Note that the project name must be globally unique. 
3.	Go to the project directory and run the `terraform init` command.
4.	Run the `terraform plan -var-file=settings.tfvars` command and check whether any error occurs. If an error occurs, check whether the configuration items are valid in Step 2.
5.	Run the `terraform apply -var-file=settings.tfvars` command. Enter `yes` after the self-check succeeds. The command output is as follows:
![CMDB-terraform](../img/CMDB-terraform.png)
6.	You can log on to the Cloud Config console by using the management account. In the left-side navigation pane, click Account Group to check whether the global account group is created. To check whether data delivery is configured, choose Settings > Delivery Channels, and then click the Deliver Logs to Log Service tab. 
7.	You can use the log archive account to log on to the Log Service console. Go to the project configured in the terraform configuration file. You can confirm that data is successfully delivered on the Consumption Preview page for the corresponding Logstore. 

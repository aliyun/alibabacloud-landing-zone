# Enterprise-level centralized audit architecture

[中文](./README.md)｜English

## Solution overview

This solution uses Terraform for automated execution. In the resource directory of the management account, the user specifies a log archive account. Terraform creates a Log Service (SLS) Logstore and Object Storage Service (OSS) buckets in the log archive account to store the audit logs of all member accounts. Log on to the ActionTrail console by using the management account, you can create a trail to continuously save ActionTrail events to the Logstore and OSS buckets of the log archive account. 

## Prerequisites

-	Terraform is installed in the current environment. 
-	The resource directory is enabled for the management account, and SLS is enabled for the log archive account. 
-	The AccessKey pair of the RAM user is obtained. The RAM user is granted permissions to assume RAM roles. To meet these requirements, create a RAM user in your management account and generate an AccessKey pair for the RAM user. Then, attach the `AliyunSTSAssumeRoleAccess`, `AliyunActionTrailFullAccess`, and `AliyunResourceDirectoryReadOnlyAccess` policies to the RAM user. 

## Procedure

1.	Step 1 Download the code package and decompress it to a directory. 
2.	Step 2 Open the settings.tfvars file by using the editor and modify the configuration items in the file based on the remarks. 
3.	Step 3 Run the `terraform init` command in the directory.
4.	Step 4 Run the `terraform plan -var-file=settings.tfvars` command and check whether any error occurs. If an error occurs, check whether the configuration items are valid in Step 2. 
5.	Step 5 Run the `terraform apply -var-file=settings.tfvars` command. Enter `yes` after the self-check succeeds. If an error occurs, troubleshoot the error based on the error message and check whether the name is the same or the required permissions are not granted. The following figure shows the check result.
![ActionTrail-apply](../img/ActionTrail-apply.png)
6.	Step 6 Use the log archive account to log on to the Log Service console. You can confirm that the project and Logstore are created and the audit logs of all member accounts in the resource directories are delivered. In addition, OSS buckets are created and used to collect the ActionTrail data of all member accounts. 


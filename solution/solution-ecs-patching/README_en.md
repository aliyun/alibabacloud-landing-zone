# Automating Operating System Patching with OOS

[中文](README.md) | [English](README_en.md)

This solution utilizes OOS to meet the operational needs of centralized scanning, monitoring, and installation of patches in a multi-account environment. This code repository implements the automation of this solution using Terraform and Resource Orchestration Service (ROS).

## Usage Steps

The automation of this solution consists of four steps:

### Step 1: Preparation

Navigate to `step1-preparation` and create the required roles and permissions in the shared services account, and delegate the ROS administrator to the shared services account. This step should be executed in resource directory management account. You can create a `tfvars.json` file to configure the parameters and deploy using the `terraform apply -var-file=tfvars.json` command.

If you have already completed the configuration of delegating the administrator, you can import it using the following `terraform import` command to avoid interruptions during execution.

```bash
terraform import -var-file=tfvars.json alicloud_resource_manager_delegated_administrator.master ${Account ID}:ros.aliyuncs.com
```


| **Parameter Name** | **Example Value** | **Description** |
| --- | --- | --- |
| region | cn-shanghai | Deployment region |
| share_services_account_id | 11531044***** | ID of the shared services account |
| oss_assume_role | EcsPatchingAutomationTriggerRole | Role in the shared services account. In this step, this role will be created in the shared services account, and OOS will assume this role to trigger the execution of the template |
| oos_cross_account_assume_role | EcsPatchingAutomationRole | Role in the other workload accounts that require unified operation and maintenance. This role will not be created in this step. You can create this role across accounts using Step 2, and OOS will assume this role to perform patching across accounts |

### Step 2: Create Roles Across Accounts

Navigate to `step2-create-cross-account-role` and use the ROS template in `stack-group-template.yaml` to create an ROS stack group in the shared services account. This stack group will create the required roles in the workload accounts that need unified operation and maintenance.

| **Parameter Name** | **Example Value** | **Description** |
| --- | --- | --- |
| RoleName | EcsPatchingAutomationRole | Role name |
| PolicyName | EcsPatchingAutomationRolePolicy | Policy name |
| AssumeRolePrincipalAccount |  | Trusted account for the role. If left empty, the current account is used by default |
| AssumeRolePrincipalRole | EcsPatchingAutomationTriggerRole | Role in the trusted account that is allowed to assume this role. Enter the role name created in the shared services account in Step 1 |

### Step 3: Distribute Patch Baseline

Navigate to `step3-distribute-patch-baseline` and use the ROS template in `stack-template.yaml` to create an ROS stack in the shared services account. This stack will distribute the patch baseline across accounts.

| **Parameter Name** | **Example Value** | **Description** |
| --- | --- | --- |
| StackGroupName | distribute-patch-baseline | Name of the created ROS stack group. Note that the stack group name must not duplicate existing names |
| SourcePatchBaseline | centos-default-patch | Patch baseline to be distributed. Enter the name of the patch baseline |
| TargetRdFolderIds | ["fd-1xxx", "fd-2xxx"] | Target RD folder IDs. The patch baseline will be distributed to all accounts under this RD folder |
| TargetRegionIds | ["cn-hangzhou", "cn-shanghai"] | Target regions where the patch baseline will be distributed |
| AutoDeployment | true | Automatically create or delete the patch baseline when members are added or removed from the RD folder |

### Step 4: Deploy Automation Process

Navigate to `step4-automation-deployment` and create a custom OOS task template in the shared services account. This step should be executed within the shared services account. You can create a `tfvars.json` file to configure the parameters and deploy using the `terraform apply -var-file=tfvars.json` command.

| **Parameter Name** | **Example Value** | **Description** |
| --- | --- | --- |
| region | cn-shanghai | Deployment region |
| oss_assume_role | EcsPatchingAutomationTriggerRole | Role in the shared services account created in Step 1. OOS will assume this role to trigger the execution of the template |
| oos_cross_account_assume_role | EcsPatchingAutomationRole | Role created in the other workload accounts that require unified operation and maintenance in Step 2. OOS will assume this role to perform patching across accounts |
| approverRamUserName | approver | RAM user allowed to approve the operation and maintenance process. This RAM user must have read and write permissions as shown below. This RAM user can approve/reject the operation and maintenance process |
| approverWebHookUrl | Valid WebHook URL | When manual approval is required, a message notification will be sent via this WebHook |
| patchingWebHookUrl | Valid WebHook URL | During the operation and maintenance process, a message notification will be sent via this WebHook. You can use this WebHook to send notifications to the business team |

The RAM user allowed to approve the operation and maintenance process must have the following read and write permissions:

```json
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "oos:GetExecutionTemplate",
        "oos:ListTaskExecutions",
        "oos:ListExecutions",
        "oos:NotifyExecution"
      ],
      "Resource": "*"
    }
  ]
}
```
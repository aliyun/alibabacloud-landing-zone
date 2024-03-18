# Golden Image Automation Pipeline

[中文](README.md) | [English](README_en.md)

This solution provides an automated pipeline deployed using Terraform. With this Terraform template, you can deploy the Golden Image automation pipeline with a single click. The template parameters are as follows.

| **Parameter Name** | **Example Parameter Value** | **Description** |
| --- | --- | --- |
| imageFamily | GoldenImage | Configures the image family for the newly built Golden Image, used to group a set of Golden Images with the same purpose. You can modify this parameter when triggering the image build process. |
| imageOSAndVersion | CentOS-7.9 | Details of the OS for the Golden Image. It is recommended to use the format OSName-OSVersion. You can modify this parameter when triggering the image build process. |
| imageVersion | 1 | Version number of the Golden Image. Each time the Golden Image is updated, the version number needs to be incremented. You can modify this parameter when triggering the image build process. |
| cidrVpc, cidrVSwitch | Valid IPV4 subnet, 10.0.0.0/16 | This automation solution automatically creates a VPC, subnet, and security group for the intermediate ECS instance used during the image build process. You can choose existing subnets and security groups when triggering the image build process. |
| zoneId | cn-hangzhou-k | Availability zone where the new subnet will be created. |
| instanceType | ecs.c6.large | Instance type of the ECS instance created during the image build process. You can modify this parameter when triggering the image build process. |
| internetMaxBandwidthOut | 10 | Public bandwidth allocated to the ECS instance created during the image build process. Set to 0 to disable public network access. You can modify this parameter when triggering the image build process. |
| approverRamUserName | approver | RAM user authorized to approve the image build process. This RAM user needs to have the read/write permissions shown below. Alternatively, you can directly grant the AliyunOOSFullAccess permission. This RAM user can approve/reject the Golden Image. You can modify this parameter when triggering the image build process. |
| webHookUrl | Valid WebHook URL | When enabling image vulnerability scanning or requiring manual approval during the image build process, notifications will be sent through this WebHook URL. You can modify this parameter when triggering the image build process. |

The RAM user needs to have the following permission to approve the image build workflow:
```
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
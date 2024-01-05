# Golden Image 自动化 Pipeline

本方案提供了基于 Terraform 部署的自动化 Pipeline，通过该 Terraform 模版可以一键部署 Golden Image 自动化 Pipeline，其模板入参如下。

| **参数名称** | **参数值示例** | **描述** |
| --- | --- | --- |
| imageFamily | GoldenImage | 配置新构建的 Golden Image 的镜像族系，用来聚合一组同一用途的 Golden Image。您可以在触发镜像构建的流程时修改该参数。 |
| imageOSAndVersion | CentOS-7.9 | Golden Image 的OS详情。建议格式为 OSName-OSVersion。您可以在触发镜像构建的流程时修改该参数。 |
| imageVersion | 1 | Golden Image 的版本号。每次更新 Golden Image 时，需要同时升级版本号。您可以在触发镜像构建的流程时修改该参数。 |
| cidrVpc
cidrVSwitch | 有效的 IPV4 网段，10.0.0.0/16 | 该自动化方案会自动创建 VPC、交换机和安全组，供镜像构建过程中创建中转的 ECS 实例使用。您可以在触发镜像构建的流程时选择其他已有的交换机和安全组。 |
| zoneId | cn-hangzhou-k | 新建的交换机所在的可用区。 |
| instanceType | ecs.c6.large | 镜像构建过程中创建的 ECS 实例的实例规格。您可以在触发镜像构建的流程时修改该参数。 |
| internetMaxBandwidthOut | 10 | 镜像构建过程中创建的 ECS 实例的公网带宽。为 0 时表示不分配公网地址。您可以在触发镜像构建的流程时修改该参数。 |
| approverRamUserName | approver | 允许审批镜像构建流程的 RAM 用户。该 RAM 用户至少需要具有下方所示的读写权限，您也可以直接授予其 AliyunOOSFullAccess 权限。该 RAM 用户可以批准/拒绝 Golden Image。您可以在触发镜像构建的流程时修改该参数。 |
| webHookUrl | 有效的 WebHook 链接 | 在镜像构建流程中开启镜像漏洞扫描或者需要人工审批时，会通过该 WebHook 发送消息通知。您可以在触发镜像构建的流程时修改该参数。 |
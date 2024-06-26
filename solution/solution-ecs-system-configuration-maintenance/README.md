# 通过OOS实现主机系统与软件配置自动化运维

基于 OOS 和云助手，满足多账号体系下，通过脚本命令对主机系统与软件配置进行运维的需求。本代码仓库通过 Terraform 和资源编排（ROS）实现该方案的自动化。

## 使用步骤

本方案的自动化共分为三个步骤：

### Step1. 准备工作

进入 `step1-preparation`，在共享服务账号中创建所需角色和权限，并委派 ROS 管理员给共享服务账号。该步骤使用资源目录管理账号的身份运行。您可以新建 `tfvars.json` 文件并配置参数，并通过 `terraform apply -var-file=tfvars.json` 命令进行部署。

如果已经完成了委派管理员的配置，可以使用如下的 `terraform import` 导入进来，避免执行时报错。

```
terraform import -var-file=tfvars.json alicloud_resource_manager_delegated_administrator.master ${账号 ID}:ros.aliyuncs.com
```

| **参数名称** | **参数值示例** | **描述** |
| --- | --- | --- |
| region | cn-shanghai | 部署地域 |
| share_services_account_id | 11531044***** | 共享服务账号 ID |
| oss_assume_role | EcsCommandRunningAutomationTriggerRole | 共享服务账号中的角色。该步骤中，会为您在共享服务账号中创建该角色，OOS 会扮演该角色来触发自动化运维模版的执行 |
| oos_cross_account_assume_role | EcsCommandRunningAutomationRole | 需要统一运维的其他业务账号中的角色。该步骤中不会创建该角色，您可以通过 Step2 统一跨账号创建角色，OOS 会扮演该角色跨账号执行脚本命令，完成配置运维 |

### Step2. 跨账号创建角色

进入 `step2-create-cross-account-role`，使用 `stack-group-template.yaml` 中的 ROS 模版在共享服务账号中创建资源编排（ROS）的资源栈组，跨账号给所有需要统一运维的业务账号创建所需角色。

| **参数名称** | **参数值示例** | **描述** |
| --- | --- | --- |
| RoleName | EcsCommandRunningAutomationRole | 角色名称 |
| PolicyName | EcsCommandRunningAutomationRolePolicy | 策略名称 |
| AssumeRolePrincipalAccount |  | 该角色可信的账号。置空，则默认为当前账号。 |
| AssumeRolePrincipalRole | EcsCommandRunningAutomationTriggerRole | 允许扮演该角色的可信账号下的角色。这里需要填写 Step1 中在共享服务账号中创建出来的角色名称 |

### Step3. 部署自动化运维流程

进入 `step3-automation-deployment`，在共享服务账号中创建 OOS 自定义任务模版。该步骤请使用共享服务账号的身份运行。您可以新建 `tfvars.json` 文件并配置参数，并通过 `terraform apply -var-file=tfvars.json` 命令进行部署。

| **参数名称** | **参数值示例** | **描述** |
| --- | --- | --- |
| region | cn-shanghai | 部署地域 |
| oss_assume_role | EcsCommandRunningAutomationTriggerRole | Step1 中创建的共享服务账号中的角色。OOS 会扮演该角色来触发自动化运维模版的执行 |
| oos_cross_account_assume_role | EcsCommandRunningAutomationRole | Step2 中在需要统一运维的其他业务账号中创建的角色。OOS 会扮演该角色跨账号执行脚本命令，完成配置运维 |
| approverRamUserName | approver | 允许审批运维流程的 RAM 用户。该 RAM 用户至少需要具有下方所示的读写权限，您也可以直接授予其 AliyunOOSFullAccess 权限。该 RAM 用户可以批准/拒绝运维流程。 |
| approverWebHookUrl | 有效的 WebHook 链接 | 在需要人工审批时，会通过该 WebHook 发送消息通知。|
| commandRunningWebHookUrl | 有效的 WebHook 链接 | 在运维流程运行时，会通过该 WebHook 发送消息通知，您可以使用该 WebHook 给业务团队发送通知。 |

允许审批运维流程的 RAM 用户至少需要具有如下所示读写权限：

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

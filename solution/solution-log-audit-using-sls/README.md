# 通过SLS实现云产品日志统一归集与审计

基于 SLS 日志审计服务，在多账号体系下，实现云产品审计日志的自动化中心化采集、分析与归档。本代码仓库通过 Terraform 实现该方案的自动化。

## 准备工作

本方案 Terraform 脚本，全部使用资源目录 Master 身份运行，请确保该身份具有配置管控策略以及 AssumeRole 的权限。

## 使用步骤

本方案的自动化共分为四个步骤：

### Step1. 开启 SLS 日志审计

进入 `step1-create-log-audit-application`，在日志账号中，开启多账号全局的 SLS 日志审计。您可以新建 `tfvars.json` 文件并配置参数，并通过 `terraform apply -var-file=tfvars.json` 命令进行部署。

| **参数名称** | **参数值示例** | **描述** |
| --- | --- | --- |
| logarchive_central_region | cn-shanghai | 审计日志归集到的中心地域 |
| logarchive_account_id | 11531044***** | 日志账号 ID |
| audit_logs | {"cloudconfig_change_enabled":"true","cloudconfig_change_ttl":"180","oss_access_enabled":"true"} | 需要开启的审计日志的配置 |

其中 `audit_logs` 支持的审计日志配置，请参考[帮助文档](https://help.aliyun.com/zh/sls/user-guide/use-terraform-to-configure-log-audit-service#section-x0i-x32-lch)。

该步骤输出参数如下：

| **参数名称** | **参数值示例** | **描述** |
| --- | --- | --- |
| logarchive_central_sls_project | slsaudit-center-11531044*****-cn-shanghai | 审计日志归集到的中心 SLS Project 名称 |

### Step2. 跨账号将其他数据源日志投递到中心日志库

进入 `step2-delivery-to-central-logarchive`，通过 SLS 数据加工的功能，将其他账号 SLS logstore 中的日志投递归集到日志账号的中心日志库中。您可以新建 `tfvars.json` 文件并配置参数，并通过 `terraform apply -var-file=tfvars.json` 命令进行部署。

| **参数名称** | **参数值示例** | **描述** |
| --- | --- | --- |
| delivery_region | cn-hangzhou | 待投递的 SLS Project 所在地域 |
| delivery_account_id | 10130264**** | 待投递的账号 ID |
| logarchive_central_region | cn-shanghai | 审计日志归集到的中心地域 |
| logarchive_account_id | 11531044***** | 日志账号 ID |
| delivery_sls_project | slsaudit-other | 待投递的 SLS Project 名称 |
| delivery_sls_logstore | other-log | 待投递的 SLS Logstore 名称 |
| central_sls_project | slsaudit-center | 审计日志归集的中心 SLS Project 名称 |
| central_sls_logstore_name | oss-log | 投递到的中心 SLS Logstore 名称 |
| is_central_sls_logstore_existed | false | 如果投递到的中心 SLS Logstore 已经存在，请设置为 true，否则，会按照 central_sls_logstore_name 名称新建 |
| delivery_ram_name_prefix | other- | 会在待投递的账号中创建用于数据加工的 RAM 角色，您可以自定义改 RAM 角色的名称前缀 |
| logarchive_ram_role_name | logaudit-post-role | SLS 会扮演该日志账号中的角色，用来将日志写入到中心日志库 |
| is_logarchive_ram_role_existed | false | 如果 logarchive_ram_role_name 指定的角色已经存在，请设置为 true，否则，会自动按照 logarchive_ram_role_name 名称新建 |

### Step3. 日志冷归档到 OSS

进入 `step3-cold-archive-to-oss`，通过 SLS 数据导出的功能，将日志账号 SLS logstore 中的日志导出到 OSS 进行冷归档。您可以新建 `tfvars.json` 文件并配置参数，并通过 `terraform apply -var-file=tfvars.json` 命令进行部署。

| **参数名称** | **参数值示例** | **描述** |
| --- | --- | --- |
| logarchive_central_region | cn-shanghai | 审计日志归集到的中心地域 |
| logarchive_account_id | 11531044***** | 日志账号 ID |
| central_sls_project | slsaudit-center | 审计日志归集的中心 SLS Project 名称 |
| oss_bucket_name | audit-log-archive | 日志冷归档到的 OSS Bucket 名称 |
| is_oss_bucket_existed | false | 如果 oss_bucket_name 指定的 OSS Bucket 已经存在，请设置为 true，否则，会自动按照 oss_bucket_name 名称新建 |
| central_sls_logstore_exports | [{"logstore_name":"cloudconfig_log","oss_bucket_directory":"cloudconfig"},{"logstore_name":"oss_log","oss_bucket_directory":"oss"}] | 需要冷归档的 SLS Logstore 配置列表 |
| logarchive_ram_role_name | audit-log-archive-to-oss-role | SLS 会扮演该日志账号中的角色，用来将日志导出到 OSS |
| is_logarchive_ram_role_existed | false | 如果 logarchive_ram_role_name 指定的角色已经存在，请设置为 true，否则，会自动按照 logarchive_ram_role_name 名称新建 |

该步骤输出参数如下：

| **参数名称** | **参数值示例** | **描述** |
| --- | --- | --- |
| oss_export_job_ids | ["cold-logarchive-1","cold-logarchive-2"] | 日志归档任务的任务 ID 列表 |

### Step4. 设置管控策略

进入 `step4-add-control-policy`，设置管控策略，禁止删除日志审计与归档使用的 SLS Project、OSS Bucket 等资源。您可以新建 `tfvars.json` 文件并配置参数，并通过 `terraform apply -var-file=tfvars.json` 命令进行部署。

| **参数名称** | **参数值示例** | **描述** |
| --- | --- | --- |
| logarchive_account_id | 11531044***** | 日志账号 ID |
| central_sls_project | slsaudit-center | 审计日志归集的中心 SLS Project 名称 |
| oss_bucket_name | audit-log-archive | 日志冷归档到的 OSS Bucket 名称 |
| oss_export_job_ids | ["cold-logarchive-1","cold-logarchive-2"] | 日志归档任务的任务 ID 列表。您可以从 step3 输出的 `oss_export_job_ids` 中获取 |
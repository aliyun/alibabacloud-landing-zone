# 通过KMS实现固定AccessKey的集中管控

通过 KMS 实现固定 AccessKey 的统一管理、加密存储、自动轮转，降低凭证泄露风险，提升安全水位，满足规范要求。本代码仓库通过 Terraform 实现该方案的自动化部署。

## 使用步骤

本方案共分为四个步骤：

### Step1. 共享 VPC

如果您需要跨账号使用 KMS 实例，进入 `step1-share-vswitch`，通过资源共享，将需要访问 KMS 实例的 VPC 共享给共享服务账号（KMS 实例将创建在共享账号中）。该步骤使用应用账号的身份运行。您可以新建 `tfvars.json` 文件并配置参数，并通过 `terraform apply -var-file=tfvars.json` 命令进行部署。更多信息请参考[同地域多VPC访问KMS实例](https://help.aliyun.com/zh/kms/user-guide/access-a-kms-instance-from-multiple-vpcs-in-the-same-region)。

| **参数名称** | **参数值示例** | **描述** |
| --- | --- | --- |
| region | cn-shanghai | 部署地域 |
| vswitch_ids | ["vsw-bp18*********"] | 需要跨账号访问 KMS 实例的 VPC 下的任意 VSwitch |
| resource_share_name | kms-vpc | 共享单元的名称 |
| resource_share_target_id | 12540046***** | 共享目标的账号 ID，上述 VSwitch 会共享给该账号 |

### Step2. 创建 KMS 实例

进入 `step2-create-kms-instance`，在共享服务账号中创建 KMS 实例。该步骤使用共享服务账号的身份运行。您可以新建 `tfvars.json` 文件并配置参数，并通过 `terraform apply -var-file=tfvars.json` 命令进行部署。

| **参数名称** | **参数值示例** | **描述** |
| --- | --- | --- |
| region | cn-shanghai | 部署地域 |
| instance_type | software | 密钥管理类型，取值：software（软件密钥管理），hardware（硬件密钥管理） |
| performance | 1000 | 实例计算性能 |
| key_num | 1000 | 支持的最大密钥数量 |
| secret_num | 100 | 支持管理的最大凭据数量 |
| access_num | 1 | 支持的最大访问管理数量 |
| log | true | 是否开启日志分析 |
| log_storage | 1000 | 日志存储容量 |
| purchase_period | 3 | 购买时长（月） |
| auto_renew | true | 是否自动续费 |
| vpc_id | vpc-bp1cr******** | 实例 VPC ID |
| vswitch_id | vsw-bp18********* | 实例 VSwitch ID |
| zone_ids | ["cn-hangzhou-k", "cn-hangzhou-j"] | KMS实例双可用区，KMS实例在您选择的两个可用区进行业务负载均衡分配和容灾 |
| bind_vpcs | [{"vpc_id": "vpc-bp1lqqx**********","vswitch_id": "vsw-bp18o5********","vpc_owner_id": "184950******"}] | 关联的 VPC，关联后这些 VPC 可以访问该 KMS 实例。更多信息请参考[同地域多VPC访问KMS实例](https://help.aliyun.com/zh/kms/user-guide/access-a-kms-instance-from-multiple-vpcs-in-the-same-region) |

### Step3. 共享 KMS 实例

如果您需要跨账号使用 KMS 实例，进入 `step3-share-kms-instance`，通过资源共享，将共享服务账号下的 KMS 实例共享给其他应用账号。该步骤使用共享服务账号的身份运行。您可以新建 `tfvars.json` 文件并配置参数，并通过 `terraform apply -var-file=tfvars.json` 命令进行部署。更多信息请参考[多账号共享KMS实例](https://help.aliyun.com/zh/kms/user-guide/share-a-kms-instance-across-multiple-alibaba-cloud-accounts)。

| **参数名称** | **参数值示例** | **描述** |
| --- | --- | --- |
| region | cn-shanghai | 部署地域 |
| kms_instance_ids | ["kst-hzz662b622eksoqrb3nhu"] | 需要共享的 KMS 实例 ID |
| resource_share_name | kms-instance | 共享单元的名称  |
| resource_share_target_id | 184950****** | 共享目标的账号 ID，上述 KMS 实例会共享给该账号 |

### Step4. 创建 KMS 接入点

创建 KMS 接入点前，请先在 KMS 凭据管理中，创建 RAM 凭据，用来托管 AccessKey，详细信息请参考[管理及使用RAM凭据](https://help.aliyun.com/zh/kms/user-guide/manage-and-use-ram-secrets)。

创建完 RAM 凭据后，进入 `step4-create-kms-aap`，在应用账号下，给 KMS 实例创建应用接入点。该步骤使用应用账号的身份运行。您可以新建 `tfvars.json` 文件并配置参数，并通过 `terraform apply -var-file=tfvars.json` 命令进行部署。更多信息请参考[创建应用接入点](https://help.aliyun.com/zh/kms/user-guide/create-an-aap)。

| **参数名称** | **参数值示例** | **描述** |
| --- | --- | --- |
| region | cn-shanghai | 部署地域 |
| kms_instance_id | kst-hzz662****** | KMS 实例 ID |
| kms_key_id | key-hzz6638******* | 加密 RAM 凭据所使用的主密钥 ID |
| kms_managed_ram_secret_name | acs/ram/user/**** | RAM 凭据名称 |
| kms_instance_policy | {"name": "ram-secret-private","description": "", "network_rules": ["private"]} | 作用在 KMS 实例上的权限策略，只能私网访问该 KMS 实例。其中 network_rules 请填写已有的网络规则名称。 |
| kms_shared_gateway_policy | {"name": "ram-secret-public","description": "", "network_rules": ["public"]} | 作用在 KMS 共享网关上的权限策略，如果您需要云下获取 RAM 凭据，那么您需要配置该参数，通过公网访问。其中 network_rules 请填写已有的网络规则名称。 |
| aap_name | ram-secret | 接入点名称 |
| aap_description | ram secret | 接入点描述 |
| client_key_not_after | 2027-08-10 T08:03:30Z | 接入点 client key 有效期截止时间，默认为开始时间 5 年后 |
| client_key_not_before | 2022-08-10 T08:03:30Z | 接入点 client key 有效期开始时间，默认为当前时间 |
| client_key_password | ePw81zEM1t****** | Client Key 加密口令，请妥善保管 |
| private_key_data_file | clientKey_KAAP.json | 用于保存生成的接入点 private key 的文件名称，默认为 `clientKey_KAAP.${KMS 实例 ID}.json` |
### 说明
如果您需要在阿里云的云服务器ECS中访问您的OSS，您可以通过ECS实例RAM角色的方式访问OSS。在集成OSS SDK后，您需要初始化OSS客户端。该示例提供了两种初始化方式演示。本示例完成OSS客户端的初始化后，调用OSS API列举存储空间，实例角色必须具有oss:ListBuckets权限。具体操作，请参见[为RAM用户授权自定义的权限策略](https://help.aliyun.com/zh/oss/user-guide/common-examples-of-ram-policies#section-ucu-jv0-zip)。

#### 环境要求
该示例代码需要在ECS环境中执行，执行前，请确保运行环境中已配置好Python环境。

在配置访问凭证前，您需要安装OSS Python SDK。详情请参见[安装](https://help.aliyun.com/zh/oss/developer-reference/installation-14)。

通过如下命令安装alibabacloud_credentials依赖：
```bash
pip install alibabacloud_credentials
```

### credentials_default_sample
该示例代码演示了初始化凭据客户端（初始化方式可参考sdk2中的示例，本示例采用默认凭据链方式），再使用凭据客户端的凭据信息初始化OSS客户端。

Credentials工具会在环境变量中获取`ALIBABA_CLOUD_ECS_METADATA`（ECS实例RAM角色名称），若存在，程序将会通过ECS的元数据服务（Meta Data Server）获取ECS实例RAM角色的STS Token作为默认凭据信息。强烈建议配置环境变量`ALIBABA_CLOUD_ECS_IMDSV2_ENABLE=true`开启在加固模式下获取STS Token。

在ECS中配置环境变量：
```bash
export ALIBABA_CLOUD_ECS_METADATA=<role-name>
export ALIBABA_CLOUD_ECS_IMDSV2_ENABLE=true
```
在python目录下运行示例代码：
```bash
python ./oss_sdk/credentials_default_sample.py
```

### role_config_sample
该示例代码采用显示配置ecs实例角色的方式初始化凭据客户端。

该方式显示设置凭据类型为`ecs_ram_role`，Credentials工具会自动获取ECS实例绑定的RAM角色，调用ECS的元数据服务（Meta Data Server）换取STS Token，完成凭据客户端初始化。

在python目录下运行示例代码：
```bash
python ./oss_sdk/credentials_role_config_sample.py
```

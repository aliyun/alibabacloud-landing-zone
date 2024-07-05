### 说明
使用V2.0版本的阿里云SDK时，集成阿里云的Credentials工具，使您能够轻松地获取和管理访问凭证。基于实例RAM角色获取临时凭证时，Credentials工具会自动获取ECS实例绑定的RAM角色，并调用ECS的元数据服务（Meta Data Server）获取临时访问凭证，该凭证会周期性更新。

本示例完成SDK客户端的初始化后，调用API：GetCallerIdentity 获取当前调用者身份信息。
#### 环境要求
该示例代码需要在ECS环境中执行
- Python 3.x
- 安装alibabacloud_credentials依赖：
    ```bash
    pip install alibabacloud_credentials
    ```
- 安装 sts SDK，用于完成代码示例调用
    ```bash
    pip install alibabacloud_sts20150401==1.1.4
    ```

### credentials_default_sample
改示例代码采用默认凭据链的方式初始化凭据客户端。

Credentials工具会在环境变量中获取`ALIBABA_CLOUD_ECS_METADATA`（ECS实例RAM角色名称），若存在，程序将会通过ECS的元数据服务（Meta Data Server）获取ECS实例RAM角色的STS Token作为默认凭据信息。强烈建议配置环境变量`ALIBABA_CLOUD_ECS_IMDSV2_ENABLE=true`开启在加固模式下获取STS Token。

在ECS中配置环境变量：
```bash
export ALIBABA_CLOUD_ECS_METADATA=<role-name>
export ALIBABA_CLOUD_ECS_IMDSV2_ENABLE=true
```
在python目录下运行示例代码：
```bash
python ./sdk2_0/credentials_default_sample.py
```

### credentials_role_config_sample
该示例代码采用显示配置ecs实例角色的方式初始化凭据客户端。

该方式显示设置凭据类型为`ecs_ram_role`，Credentials工具会自动获取ECS实例绑定的RAM角色，调用ECS的元数据服务（Meta Data Server）换取STS Token，完成凭据客户端初始化。

在python目录下运行示例代码：
```bash
python ./sdk2_0/credentials_role_config_sample.py
```
### 其他方式
更多初始化方式，请参考[初始化凭据客户端](https://help.aliyun.com/zh/sdk/developer-reference/v2-manage-access-credentials)。

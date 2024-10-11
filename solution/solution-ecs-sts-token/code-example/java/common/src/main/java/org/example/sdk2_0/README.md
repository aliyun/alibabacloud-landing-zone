### 说明
使用V2.0版本的阿里云SDK时，集成阿里云的Credentials工具，使您能够轻松地获取和管理访问凭证。基于实例RAM角色获取临时凭证时，Credentials工具会自动获取ECS实例绑定的RAM角色，并调用ECS的元数据服务（Meta Data Server）获取临时访问凭证，该凭证会周期性更新。

本示例完成SDK客户端的初始化后，调用API：GetCallerIdentity获取当前调用者身份信息。

如果您需要从长期固定AccessKey的使用方式进行迁移，只需修改少量代码即可完成，如下图所示，左侧一栏是使用固定AccessKey初始化阿里云SDK，右侧一栏是使用Credentials工具初始化阿里云SDK。

![](./code-diff.png)

#### 环境要求
该示例代码需要在ECS环境中执行，执行前，请确保运行环境中已配置好Java和Maven。
1. Java Development Kit (JDK)：确保已安装Java 8或更高版本。
2. Apache Maven：确保已安装Maven 3.6.0或更高版本。

运行以下命令来检查Java安装：
```bash
java -version
```
运行以下命令来检查Maven安装：
```bash
maven -version
```

### CredentialsRoleConfigSample
该示例代码采用显示配置ecs实例角色的方式初始化凭据客户端。

> 推荐您使用该方式，在代码中明确指定实例角色，避免运行环境中的环境变量、配置文件等带来非预期的结果。

该方式显示设置凭据类型为`ecs_ram_role`，Credentials工具会自动获取ECS实例绑定的RAM角色，调用ECS的元数据服务（Meta Data Server）换取STS Token，完成凭据客户端初始化。

运行示例代码：
```bash
mvn exec:java -Dexec.mainClass="org.example.sdk2_0.CredentialsRoleConfigSample" -e -q
```
### 其他方式
更多初始化方式，请参考[初始化凭据客户端](https://help.aliyun.com/zh/sdk/developer-reference/v2-manage-access-credentials)。

### CredentialsDefaultSample
该示例代码采用默认凭据链的方式初始化凭据客户端。

> 除非您清楚的知道默认凭据链中凭据信息查询优先级以及您的程序运行的各个环境中凭据信息配置方式，否则不建议您使用默认凭据链，推荐您使用显式配置实例角色方式。

Credentials工具会在环境变量中获取`ALIBABA_CLOUD_ECS_METADATA`（ECS实例RAM角色名称），若存在，程序将会通过ECS的元数据服务（Meta Data Server）获取ECS实例RAM角色的STS Token作为默认凭据信息。强烈建议配置环境变量`ALIBABA_CLOUD_ECS_IMDSV2_ENABLE=true`开启在加固模式下获取STS Token。

在ECS中配置环境变量：
```bash
export ALIBABA_CLOUD_ECS_METADATA=<role-name>
export ALIBABA_CLOUD_ECS_IMDSV2_ENABLE=true
```
运行示例代码：
```bash
mvn exec:java -Dexec.mainClass="org.example.sdk2_0.CredentialsDefaultSample" -e -q
```

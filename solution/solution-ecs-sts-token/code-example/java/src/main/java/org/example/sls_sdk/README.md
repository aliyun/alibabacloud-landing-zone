### 说明
如果您需要在阿里云的云服务器ECS中访问您的SLS，您可以通过ECS实例RAM角色的方式访问SLS。在集成SLS SDK后，您需要初始化SLS客户端。该示例提供了两种初始化方式演示。

本示例完成SLS客户端的初始化后，调用SLS API列出Project信息，实例角色必须具有log:ListProject权限。具体操作，请参见[为RAM用户授权自定义的权限策略](https://help.aliyun.com/zh/oss/user-guide/common-examples-of-ram-policies#section-ucu-jv0-zip)。

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
### CredentialsDefaultSample
该示例代码演示了初始化凭据客户端（初始化方式可参考sdk2中的示例，本示例采用默认凭据链方式），再使用凭据客户端的凭据信息初始化SLS客户端。

Credentials工具会在环境变量中获取`ALIBABA_CLOUD_ECS_METADATA`（ECS实例RAM角色名称），若存在，程序将会通过ECS的元数据服务（Meta Data Server）获取ECS实例RAM角色的STS Token作为默认凭据信息。强烈建议配置环境变量`ALIBABA_CLOUD_ECS_IMDSV2_ENABLE=true`开启在加固模式下获取STS Token。

在ECS中配置环境变量：
```bash
export ALIBABA_CLOUD_ECS_METADATA=<role-name>
export ALIBABA_CLOUD_ECS_IMDSV2_ENABLE=true
```
运行示例代码：
```bash
mvn exec:java -Dexec.mainClass="org.example.sls_sdk.CredentialsDefaultSample" -e -q
```

### RoleConfigSample
该示例代码通过显示配置ECS实例RAM角色，来初始化SLS客户端。

运行示例代码：
```bash
mvn exec:java -Dexec.mainClass="org.example.sls_sdk.RoleConfigSample" -e -q
```

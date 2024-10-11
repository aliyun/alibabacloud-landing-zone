### 说明
如果您需要在阿里云的云服务器ECS中访问您的OSS，您可以通过ECS实例RAM角色的方式访问OSS。在集成OSS SDK后，您需要初始化OSS客户端。该示例提供了两种初始化方式演示。

本示例完成OSS客户端的初始化后，调用OSS API列举存储空间，实例角色必须具有oss:ListBuckets权限。具体操作，请参见[为RAM用户授权自定义的权限策略](https://help.aliyun.com/zh/oss/user-guide/common-examples-of-ram-policies#section-ucu-jv0-zip)。

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
该示例代码演示了初始化凭据客户端（初始化方式可参考sdk2中的示例，本示例采用默认凭据链方式），再使用凭据客户端的凭据信息初始化OSS客户端。

> 强烈建议使用该方式，使用Credentials工具获取STS Token并初始化OSS客户端。

Credentials工具会通过ECS的元数据服务（Meta Data Server）获取ECS实例RAM角色的STS Token作为默认凭据信息，并且支持到期自动刷新。

运行示例代码：
```bash
mvn exec:java -Dexec.mainClass="org.example.oss_sdk.CredentialsDefaultSample" -e -q
```

### RoleConfigSample
该示例代码通过`InstanceProfileCredentialsProvider`配置ECS实例RAM角色，来初始化OSS客户端。

> 不建议使用该方式，请参考`CredentialsDefaultSample.java`使用Credentials工具获取STS Token并初始化OSS客户端。

运行示例代码：
```bash
mvn exec:java -Dexec.mainClass="org.example.oss_sdk.RoleConfigSample" -e -q
```

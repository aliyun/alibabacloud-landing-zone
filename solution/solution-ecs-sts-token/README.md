# 通过ECS实例角色实现临时凭证的获取和使用
通过ECS实例角色和RAM角色关联，使用STS Token访问云资源，避免了将访问密钥硬编码在代码中，从而消除AK泄露的风险。临时凭证（STS Token）的使用有效解决了永久凭证（AK/SK）可能带来的安全风险问题。
本方案提供Java/Python/PHP代码示例，客户能够快速完成应用改造，减少开发和部署的复杂度。

若您的应用程序在获取到ECS实例角色的临时凭据以后，还需要跨账号角色扮演到其他账号，使用其他角色身份调用OpenAPI，同时您希望跨账号批量完成被扮演的角色的创建。本方案提供Terraform代码，为资源目录下的目标账号批量创建 RAM 角色。

## 使用步骤
### 目录结构说明
```
ecs-instance-role/
    ├── code-example/   
    │    ├── java/        # java示例代码
    │    ├── python/      # python示例代码
    │    └── php/         # php示例代码
    └── deployment/       # 自动化模版代码
```
### Java示例代码
```
java/src/main/java/
    └── org/                  
        └── example/              
            ├── oss_sdk/    # OSS SDK配置ECS RAM role凭证示例
            ├── sdk1_0/     # 阿里云SDK 1.0配置ECS RAM role凭证示例
            ├── sdk2_0/     # 阿里云SDK 2.0配置ECS RAM role凭证示例
            └── sls_sdk/    # SLS SDK配置ECS RAM role凭证示例
```
#### 如何运行
该示例代码需要在ECS环境中执行，执行前，请确保运行环境中已配置好Java和Maven。
1. Java Development Kit (JDK)：确保已安装Java 8或更高版本。
2. Apache Maven：确保已安装Maven 3.6.0或更高版本。

编译maven项目：
```bash
mvn compile
```
运行代码示例：
```bash
mvn exec:java -Dexec.mainClass="org.example.oss_sdk.<ClassName>" -e -q
```
### Python示例代码
```
python/         
    ├── oss_sdk/    # OSS SDK配置ECS RAM role凭证示例
    ├── sdk1_0/     # 阿里云SDK 1.0配置ECS RAM role凭证示例
    ├── sdk2_0/     # 阿里云SDK 2.0配置ECS RAM role凭证示例
    └── sls_sdk/    # SLS SDK配置ECS RAM role凭证示例
```
#### 如何运行
该示例代码需要在ECS环境中执行，执行前，请确保已经配置好Python运行环境。

详情见各示例文件夹中的README文件。

### 自动化代码
```
deployment/
    └── create_role_cross_account/  #跨账号创建所需角色
    
```
#### 如何运行
参见解决方案**通过ROS跨账号批量创建角色（可选)**章节


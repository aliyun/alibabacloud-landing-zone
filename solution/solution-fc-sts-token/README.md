# 通过FC函数角色实现临时凭证的获取和使用
通过FC函数角色和RAM角色关联，使用STS Token访问云资源，避免了将访问密钥硬编码在代码中，从而消除AK泄露的风险。临时凭证（STS Token）的使用有效解决了永久凭证（AK/SK）可能带来的安全风险问题。
本方案提供Java/Python代码示例，客户能够快速完成应用改造，减少开发和部署的复杂度。

## 使用步骤
### 目录结构说明
```
solution-fc-sts-token/
└── code-example/   
     ├── java/        # java示例代码
     ├── python/      # python示例代码
     └── nodejs/      # Node.js示例代码
```
### Java示例代码
```
java/
└── src/main/java/                
│   └── org/
│       └── example/
│           ├── aliyun_sdk/  # 阿里云SDK配置FC函数角色凭证示例
│           ├── oss_sdk/     # OSS SDK配置FC函数角色凭证示例
│           └── sls_sdk/     # SLS SDK配置FC函数角色凭证示例
└── pom.xml                 # Maven项目的配置文件
```
#### 如何运行
该示例代码需要在FC函数中执行，请确保选择Java作为FC函数的运行环境。

请您选择您的SDK类型对应的示例代码，将其打包成jar文件后上传至函数计算以运行。
请确保运行环境中已配置好Java和Maven。
1. Java Development Kit (JDK)：确保已安装Java 8或更高版本。
2. Apache Maven：确保已安装Maven 3.6.0或更高版本。

首先进入Java代码目录：
```bash
cd code-example/java
```
复制maven依赖：
```bash
mvn dependency:copy-dependencies
```
编译代码：
```bash
mkdir target/classes
javac -d target/classes -cp "target/dependency/*" src/main/java/org/example/<PathName>/<ClassName>.java
```
打包代码：
```bash
jar cvf target/<JarFileName>.jar -C target/classes . -C target/dependency .
```

注意：
请确认函数中 配置 > 运行时 > 请求处理程序 的格式为 [package].[class]::[method]。例如，当前值为 org.example.aliyun_sdk.App::handleRequest，那么在函数被触发时，将执行 org.example.aliyun_sdk 包中 App 类中的 handleRequest 函数。

### Python示例代码
```
python/         
├── aliyun_sdk/  # 阿里云SDK配置FC函数角色凭证示例
├── oss_sdk/     # OSS SDK配置FC函数角色凭证示例
└── sls_sdk/     # SLS SDK配置FC函数角色凭证示例
```
#### 如何运行
该示例代码需要在FC函数中执行，请确保选择Python作为FC函数的运行环境。

请您选择您的SDK类型对应的示例代码，复制代码后上传至函数计算运行即可。
# 通过TVM实现临时凭证的获取和使用

在典型的服务端和客户端架构下，客户端如果需要访问阿里云，比如基于OSS的用户文件上传下载、使用SLS记录客户端日志等等场景，常见的方式是在客户端使用RAM用户的访问密钥（AccessKey）来完成，但是在客户端中使用长期有效的访问密钥，可能会导致访问密钥泄露，进而引起安全问题。

本方案介绍了一种在客户端获取并使用STS临时凭证的方式，通过STS临时凭证访问阿里云，无需透露长期AccessKey，减少密钥泄露的风险。同时，通过会话权限策略，可以进行精细化的权限管控，避免越权问题。

这里针对本方案提供了Java SpringBoot的代码示例，帮助您快速完成应用改造，减少开发和部署的复杂度。

## 使用步骤

### 目录结构说明

```
.
└── code-example
    └── java
        └── spring-boot   # Java SpringBoot示例代码
```

### Java示例代码

```
java/spring-boot/src/main
    ├── java/org/example
    │       ├── Application.java
    │       ├── config
    │       │   ├── CredentialConfig.java               # 初始化凭据客户端
    │       │   └── StsClientConfig.java                # 初始化STS客户端
    │       ├── controller
    │       │   └── TvmController.java
    │       └── service
    │           ├── StsTokenVendor.java                 # 获取STS Token
    │           ├── TokenVendingMachine.java
    │           └── policy
    │               ├── PolicyGenerator.java            # 生成Session Policy
    │               └── PolicyTemplateLoader.java       # 加载权限模版
    └── resources
        ├── application.properties
        ├── policy-templates                            # 权限模版
        │   ├── OssTemplate.json
        │   └── SlsTemplate.json
        └── static                                      # 前端示例
            ├── oss.html                                # OSS示例
            └── sls.html                                # SLS示例
```

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

#### 本地运行

1. 首先您需要配置凭证信息，建议您通过环境变量进行配置：

    ```
    ALIBABA_CLOUD_ACCESS_KEY_ID=<您的AccessKey ID>;ALIBABA_CLOUD_ACCESS_KEY_SECRET=<您的AccessKey Secret>
    ```

    > 您也可以将该项目部署到阿里云上，强烈建议您使用临时凭证来代替固定AccessKey。

2. 接着您需要进行应用配置，打开 `resources/application.properties` 进行如下配置：

    ```
    # 服务启动端口个
    server.port = 7001

    # 地域，以杭州地域为例
    region.id=cn-hangzhou

    # 请填写要扮演的业务RAM角色ARN，格式为acs:ram::${账号 ID}:role/${角色名称}
    role.arn=

    # 请填写OSS Bucket名称，示例中会从该Bucket中上传下载文件
    oss.bucket=

    # 请填写SLS Project名称
    sls.project=
    ```

    同时，在`resources/static/oss.html`和`resources/static/sls.html`中配置对应的信息。

3. 启动`Application.java`，浏览器打开`resources/static/oss.html`和`resources/static/sls.html`体验Web端示例
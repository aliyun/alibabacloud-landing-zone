# 通过预签名机制在客户端实现OSS直传和下载

在典型的服务端和客户端架构下，常见的文件上传方式是服务端代理上传：客户端将文件上传到业务服务器，然后业务服务器将文件上传到OSS。在这个过程中，一份数据需要在网络上传输两次，会造成网络资源的浪费，增加服务端的资源开销。同时从OSS上传/下载文件需要使用RAM用户的访问密钥（AccessKey）来完成签名认证，但是在客户端中使用长期有效的访问密钥，可能会导致访问密钥泄露，进而引起安全问题。

本文档介绍了一种在客户端实现直接从OSS上传/下载文件的方案，避免了业务服务器中转文件，提高了上传速度，节省了服务器资源，同时，客户端基于预签名机制访问OSS，无需透露长期AccessKey，减少密钥泄露的风险。

这里针对本方案提供了相关代码示例，帮助您快速完成应用改造，减少开发和部署的复杂度。


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
    │     ├── Application.java
    │     ├── config
    │     │   ├── CredentialConfig.java           # 初始化凭据客户端
    │     │   └── OssConfig.java                  # 初始化OSS客户端等
    │     ├── controller
    │     │   ├── DownloadController.java
    │     │   └── UploadController.java
    │     ├── model
    │     │   ├── OssPostCallback.java
    │     │   ├── PostCallbackResp.java
    │     │   └── PostSignatureResp.java
    │     └── service
    │         ├── DownloadService.java             # 生成客户端可以直接下载的签名URL
    │         └── UploadService.java               # 生成客户端直传的Post Policy和签名，并处理OSS回调逻辑
    └── resources
        ├── application.properties
        └── static                                # 前端示例
            └── index.html
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

    # 请填写OSS Bucket名称，示例中会从该Bucket中上传下载文件
    oss.bucket=

    # 当前服务的请求地址，OSS会通过该地址回调到当前服务
    service.address=
    ```

    同时，在`resources/static/index.html`和`java/org/example/config/OssConfig.java`中配置对应的本地服务地址、OSS Bucket目录等信息。

3. 启动`Application.java`，浏览器打开`resources/static/index.html`体验Web端示例
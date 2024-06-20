# 通过容器服务RRSA实现临时凭证的获取和使用
通过服务账户和RAM角色关联，使用STS Token访问云资源，避免了将访问密钥硬编码在代码中，从而消除AK泄露的风险。临时凭证（STS Token）的使用有效解决了永久凭证（AK/SK）可能带来的安全风险问题。
本方案提供Java/Python代码示例，客户能够快速完成应用改造，减少开发和部署的复杂度。

## 目录结构说明
```
solution-ack-sts-token/
    └── code-example/   
         ├── java/          # java示例代码
         ├── python/        # python示例代码
         └── deploy.yaml    # ACK部署文件
```

### Java示例代码
```
java/src/main/java/
    └── org/                  
        └── example/              
            ├── oss_sdk/    # OSS SDK配置OIDC RAM角色凭证示例
            ├── sdk1_0/     # 阿里云SDK 1.0配置OIDC RAM角色凭证示例
            ├── sdk2_0/     # 阿里云SDK 2.0配置OIDC RAM角色凭证示例
            └── sls_sdk/    # SLS SDK配置OIDC RAM角色凭证示例
```

### Python示例代码
```
python/         
    ├── oss_sdk/    # OSS SDK配置OIDC RAM角色凭证示例
    ├── sdk2_0/     # 阿里云SDK 2.0配置OIDC RAM角色凭证示例
    └── sls_sdk/    # SLS SDK配置OIDC RAM角色凭证示例
```
注：阿里云V1.0 Python SDK不支持集成Credentials工具。

### ACK部署文件
通过为命名空间、服务账户和Pod加上标签或注解，可以启用ack-pod-identity-webhook组件的配置自动注入功能、指定使用服务账户的Pod挂载的OIDC Token的有效期。
关于ack-pod-identity-webhook组件配置的更多说明，请参见[ack-pod-identity-webhook](https://help.aliyun.com/zh/ack/product-overview/ack-pod-identity-webhook)。
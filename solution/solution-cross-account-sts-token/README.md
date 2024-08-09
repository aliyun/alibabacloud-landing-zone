# 通过角色扮演实现跨账号临时凭证的获取和使用

过角色链式扮演的方式，使应用基于STS临时凭证访问其他账号的云资源，保证全链路无需透露长期AccessKey，既可减少密钥泄露的风险，也可借助访问控制RAM精细化控制资源访问权限，避免权限过度分配。

本方案提供Java/Terraform等代码示例，客户能够快速完成应用改造，减少开发和部署的复杂度。

## 使用步骤

### 目录结构说明

```
.
├── code-example
│   ├── java          // Java代码示例
│   └── terraform     // Terraform代码示例
└── deployment        // 自动化部署代码
    └── create-role-cross-account
```

### Java代码示例

```
.
└── common
    └── src/main/java
        └── org
            └── example
                ├── sdk1_0    // 1.0版本SDK简单示例
                └── sdk2_0    // 2.0版本SDK简单示例
```

执行前，请确保运行环境中已配置好Java和Maven。

1. Java Development Kit (JDK)：确保已安装Java 8或更高版本。
2. Apache Maven：确保已安装Maven 3.6.0或更高版本。

### 自动化部署代码

本方案提供了基于ROS的批量跨账号创建角色的Terraform自动化代码，其模版入参输入：

| **参数名称** | **参数值示例** | **描述** |
| --- | --- | --- |
| role_name | CentralizedOperationRole | 批量创建的角色的名称 |
| policy_name | CentralizedOperationRolePolicy | 绑定到该角色的权限策略的名称 |
| policy_document | {"Version":"1","Statement":[{"Action":"ecs:*","Resource":"*","Effect":"Allow"}]} | 绑定到该角色的权限策略内容。既跨账号资源操作的所有权限。|
| assume_role_principal_account | 1254004******** | 角色的可信账号，既允许扮演到该新建角色的账号，不填默认为当前账号（运维账号）|
| assume_role_principal_type | RamRole | 可信账号下允许扮演该新建角色的对象的类型，枚举值：RamRole（RAM角色）、RamUser（RAM用户）|
| assume_role_principal_name | EcsInstanceRole | 可信账号下允许扮演该新建角色的对象的名称，如果授信对象类型为RamRole（RAM角色），那么请填写对应RAM角色名称，如果RamUser（RAM用户），那么请填写对应RAM用户名称。请确保填写的RAM角色或者RAM用户在可信账号下已经存在，否则会创建失败。|
# 阿里云轻量级Landing Zone解决方案

本解决方案由阿里云开放平台解决方案团队发布，针对多账号快速构建符合安全规范、架构完善的运行环境。在此方案中我们会提供多账号的设计、资源管理、身份权限、网络最佳实践、安全合规基线等方面的规范设计。快速搭建一个安全合规的上云登录区。

## 🏗️ 解决方案架构

### 多账号设计
- **管理账号 (Management Account)**: 负责整体治理和策略管理
- **日志账号 (Log Account)**: 集中日志收集和审计
- **共享服务账号 (Shared Service Account)**: 提供共享服务如DNS、证书等
- **安全账号 (Security Account)**: 集中安全服务和合规管理
- **业务账号**: 运维、开发、测试、生产等业务环境

### 核心组件
- **身份管理**: CloudSSO统一身份认证，RAM用户和角色管理
- **网络架构**: VPC、CEN、NAT网关、安全组等网络基础设施
- **安全合规**: 合规包、标签策略、操作审计等安全基线
- **资源管理**: 资源组、标签管理、成本控制
- **应用部署**: ECS、ALB等应用基础设施

## 📋 前置条件

### 账号要求
- 管理账号必须具有企业发票抬头，否则无法作为付款账号
- 确保管理账号具有足够的权限创建和管理子账号
- 建议在全新环境中执行，避免与现有资源冲突

### 环境要求
- Terraform >= 1.0
- aliyun provider >= 1.205.0
- Python 3.x (用于脚本执行)
- 阿里云CLI工具
- 有效的阿里云AccessKey和SecretKey

## 🚀 快速开始

### 1. 环境准备
```bash
# 克隆项目
git clone <repository-url>
cd solution-light-landing-zone

# Python运行环境初始化
pip3 install alibabacloud_tea_openapi
pip3 install alibabacloud_cbn20170912==1.0.17
pip3 install alibabacloud_tag20180828==1.0.4
pip3 install alibabacloud_tea_console
pip3 install alibabacloud_darabonba_env
pip3 install alibabacloud_sts20150401

# 配置阿里云凭证
export ALICLOUD_ACCESS_KEY="your-access-key"
export ALICLOUD_SECRET_KEY="your-secret-key"
```

### 2. 配置文件设置
编辑 `settings.tfvars` 文件，配置以下关键参数：

```hcl
# 基础配置
light_landingzone_region  = "cn-shanghai"  # 部署区域
management_account_id     = "your-management-account-id"
payer_account_id          = "your-payer-account-id"

# 账号信息 (通过云治理中心搭建)
log_account_id            = "your-log-account-id"
shared_service_account_id = "your-shared-service-account-id"
security_account_id       = "your-security-account-id"

# CloudSSO配置
cloudsso_directory_name   = "your-directory-name"
```

### 3. 执行部署
```bash
# 一键部署所有组件
chmod +x run.sh
./run.sh
```

## 📝 详细部署步骤

### 第一阶段：账号和身份管理
1. **创建成员账号** (`step/resource-create-account`)
   - 在资源目录中创建业务账号
   - 配置账号基本信息

2. **创建CloudSSO用户** (`step/cloudsso-create-user`)
   - 配置统一身份认证
   - 创建SSO用户和权限

3. **创建RAM用户和角色** (`step/iam-create-user-api-key`)
   - 创建程序化访问用户
   - 配置API访问密钥

4. **授权RAM用户策略** (`step/iam-authorize-user-role`)
   - 分配系统策略
   - 配置权限边界

### 第二阶段：资源管理
5. **创建资源组** (`step/resource-create-group`)
   - 按业务划分资源组
   - 配置资源组织结构

6. **创建标签策略** (`step/resource-tag`)
   - 定义标签规范
   - 配置标签策略

### 第三阶段：安全合规
7. **启用合规规则** (`step/com-config-compliance-pack`)
   - 配置合规包模板
   - 启用安全基线检查

### 第四阶段：网络架构
8. **创建VPC和交换机** (`step/network-create-vpc`)
   - 构建基础网络架构
   - 配置子网划分

9. **构建DMZ VPC** (`step/network-build-vpc-dmz`)
   - 创建DMZ网络区域
   - 配置安全隔离

10. **创建CEN并连接VPC** (`step/network-attach-cen`)
    - 配置云企业网
    - 实现网络互联

11. **配置路由** (`step/network-config-route`)
    - 设置网络路由策略
    - 配置流量转发

### 第五阶段：应用部署
12. **部署ECS和ALB** (`step/application-deploy-ecs-alb`)
    - 部署应用服务器
    - 配置负载均衡

## 🛠️ 模块说明

### 核心模块 (`modules/`)
- `terraform-alicloud-landing-zone-resource-structure/`: 资源结构管理
- `terraform-alicloud-landing-zone-log-archive/`: 日志归档管理
- `networking/`: 网络基础设施模块
- `compliance-pack/`: 合规包管理
- `create-cloudsso-and-access/`: CloudSSO和访问管理

### 部署步骤 (`step/`)
每个步骤都是独立的Terraform配置，可以单独执行或按顺序执行。

## 🔧 自定义配置

### 修改账号结构
在 `settings.tfvars` 中修改账号配置：
```hcl
# 业务账号配置
ops_display_name        = "运维账号"
daily_display_name      = "测试账号"
prod_display_name       = "生产账号"
```

### 调整网络配置
修改VPC和子网配置：
```hcl
# VPC配置
vpc_cidr_block = "10.0.0.0/16"
vswitch_cidr_blocks = ["10.0.1.0/24", "10.0.2.0/24"]
```

### 配置合规规则
选择适合的合规包模板：
```hcl
# 合规包模板ID
# "OSS合规管理最佳实践": "ct-a5edff4e06a3004a5e15"
# "网络合规管理最佳实践": "ct-d254ff4e06a300cfc654"
# "账号权限合规管理最佳实践": "ct-d264ff4e06a300a9c2d0"
```

## ⚠️ 重要注意事项

### 部署前检查
- **环境隔离**: 建议在全新环境中执行，避免与现有资源冲突
- **权限验证**: 确保管理账号具有足够的权限创建子账号和资源
- **配置备份**: 部署前备份现有配置，避免数据丢失

### 执行过程中
- **超时处理**: 某些操作可能超时，可以重试执行
- **状态管理**: 每个步骤执行完成后检查Terraform状态
- **日志监控**: 关注执行日志，及时处理错误

### 部署后验证
- **账号验证**: 确认所有账号创建成功
- **网络连通性**: 测试VPC间网络连通性
- **安全策略**: 验证安全组和合规规则生效
- **应用访问**: 测试应用服务可正常访问

## 🔍 故障排除

### 常见问题
1. **账号创建失败**
   - 检查管理账号权限
   - 验证账号ID配置正确性

2. **网络连接问题**
   - 检查CEN配置
   - 验证路由表设置

3. **合规规则不生效**
   - 确认合规包模板ID正确
   - 检查规则参数配置

### 清理资源
如需清理已部署的资源，请按相反顺序执行：
```bash
# 按步骤清理资源
cd step/application-deploy-ecs-alb && terraform destroy
cd ../network-config-route && terraform destroy
# ... 继续其他步骤
```

## 📞 技术支持

如遇到问题，请：
1. 检查执行日志和错误信息
2. 参考阿里云官方文档
3. 联系阿里云技术支持

## 📄 许可证

本解决方案遵循相关开源许可证，具体请查看LICENSE文件。

---

**注意**: 本方案为生产环境设计，请在测试环境充分验证后再部署到生产环境。
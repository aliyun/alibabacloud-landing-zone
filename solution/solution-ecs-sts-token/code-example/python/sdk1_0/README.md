### 说明
阿里云V1.0 SDK是较早使用版本，稳定性良好，不少老用户习惯于原版SDK的开发，本示例为用户提供一个简练的使用指南。对于新用户则建议直接使用新版SDK，老用户也建议尽早迁移到新版SDK。

本示例完成SDK客户端的初始化后，调用VPC API查询已创建的vpc，实例角色必须具有vpc:DescribeVpcs权限。具体操作，请参见[为RAM用户授权自定义的权限策略](https://help.aliyun.com/zh/oss/user-guide/common-examples-of-ram-policies#section-ucu-jv0-zip)。
#### 环境要求
该示例代码需要在ECS环境中执行，执行前，请确保运行环境中已配置好Python环境。

### role_config_sample
该示例代码通过配置ECS实例角色名完成客户端初始化。

在python目录下运行示例代码：
```bash
python ./sdk1_0/role_config_sample.py
```

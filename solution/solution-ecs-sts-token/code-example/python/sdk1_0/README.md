### 说明
阿里云V1.0 SDK是较早使用版本，稳定性良好，不少老用户习惯于原版SDK的开发，本示例为用户提供一个简练的使用指南。对于新用户则建议直接使用新版SDK，老用户也建议尽早迁移到新版SDK。

本示例完成SDK客户端的初始化后，调用API：GetCallerIdentity 获取当前调用者身份信息。
#### 环境要求
该示例代码需要在ECS环境中执行
- Python 2.7 或 3.x 
- 安装 SDK 核心库 Core
    ```bash
    pip install aliyun-python-sdk-core
    ```
- 安装 sts SDK，用于完成代码示例调用
    ```bash
    pip install aliyun-python-sdk-sts==3.1.2
    ```

### role_config_sample
该示例代码通过配置ECS实例角色名完成客户端初始化。

在python目录下运行示例代码：
```bash
python ./sdk1_0/role_config_sample.py
```

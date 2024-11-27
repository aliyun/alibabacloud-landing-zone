# 基于函数计算实现从IdP到IDaaS的人员定时同步方案

本方案通过函数计算定时调用IDaaS同步的OpenAPI，来实现按照自定义周期进行人员自动从IdP到IDaaS同步。通过FC函数角色和RAM角色关联，使用STS Token访问云资源，避免了将访问密钥硬编码在代码中，从而消除AK泄露的风险。临时凭证（STS Token）的使用有效解决了永久凭证（AK/SK）可能带来的安全风险问题。 本方案提供Python代码示例，客户能够快速完成函数计算部署，减少开发和部署的复杂度。

## 如何运行
该示例代码需要在FC函数中执行，请确保选择Python作为FC函数的运行环境。
请您选择您的SDK类型对应的示例代码，复制代码后上传至函数计算运行即可。
需要配置以下环境变量：
{'IDAAS_EIAM_ENDPOINT',
  'INSTANCE_ID',
  'TARGET_ID',
  'TARGET_TYPE'
 }
# 解决方案《基于阿里云ARMS Grafana构建线下IDC Zabbix和云上Prometheus的统一监控》代码仓库

本方案用阿里云的一个单独的地域模拟线下IDC环境,在其中通过terraform自动化脚本部署:

* 一台ECS作为Zabbix Server服务器提供监控服务;
* 一台ECS作为Zabbix Agent,它是Zabbix Server的监控对象和数据来源;
* 相关必要的VPC、交换机、安全组。

然后,把该Zabbix监控接入到阿里云统一监控平台--应用实时监控服务ARMS中的Grafana工作区;
再把云上Prometheus监控接入到上述Grafana工作区;
最后,把线下IDC Zabbix监控和云上Prometheus监控集成到同一个仪表盘文件夹。

## 使用步骤

用阿里云Terraform Explorer创建调试任务并创建资源
确保已登录阿里云账号（并保证有足够的权限），并浏览器访问
https://api.aliyun.com/terraform;

* 点击示例模板--创建模板--创建空白模板，填写模板名称和模板描述；
* 打开编辑模式；
* 贴入本代码仓库中的代码；
* 点击发起调试；
* 点击预览并执行；

整个创建资源的过程大概需要3~5分钟,执行成功后,点击执行详情,把日志拉到最底部,可以看到该terraform脚本设置的三个Outputs输出,其中第一个和第三个参数之后会用到。
后续步骤请参考解决方案文档。
# 企业多账号场景下订阅特定资源配置变更

## 方案介绍
当企业有多个云账号，不同账号内会有许多不同的云资源。企业内部安全合规团队如果想重点关注某些特定规则的资源变更情况。比如只关注特定全名规则的RAM角色是否发生了修改，并且希望把这份修改事件投递到企业内部的管理系统用于后续审计分析。

## 前置条件
- 确保已在「企业管理账号」中开通资源目录，并且将其他账号邀请到资源目录中。具体操作，请参见[云治理中心-资源结构初始化](https://help.aliyun.com/document_detail/254876.html "云治理中心-资源结构初始化")。
- 确保已在「企业管理账号」中开启多账号的配置审计功能。具体操作，请参见[云治理中心-统一配置防护规则](https://help.aliyun.com/document_detail/321927.html "云治理中心-统一配置防护规则")。
- 确保在各个成员账号中开通事件总线。具体操作，请参见开通事件总线。
- 确保在「日志账号」中开通事件总线及函数计算、开通日志服务、配置OSS对象用于上传函数计算的代码，配置相应的VPC网络及安全组用于配置数据库及函数计算资源。创建相应的数据库，相应的数据表及授权。具体操作，请参见[开通事件总线](https://help.aliyun.com/product/161886.html "开通事件总线") 和 [开通函数计算](https://help.aliyun.com/product/50980.html "开通函数计算")。



## 操作步骤

1. 下载代码包，解压到某个目录。目录结构如下：
   ```
   ├── python_script               			// 通过Python实现的快速搭建脚本
     ├── __init__.py        				
	 ├── config_default.py        	// 定义资源管理主账号操作OpenAPI所使用到的AK/SK及其他配置
     ├── subscription.py          
	 ├── index.py             			// 定义函数计算中的主函数,无需修改
	 ├── requirement.txt 			// 定义依赖的包 
   ```

2. 使用Python执行自动化配置
2.1 使用编辑器打开 `python/config_default.py` 这个文件，根据注释，修改该文件内的配置项.
```
# 资源管理账号拥有资源目录权限的AK
master_account_access_key = ""   			 
# 资源管理账号拥有资源目录权限的SK
master_account_secret_key  = ""   			  
# 资源部署所在的Region
region_id = ""  							  			 
# 事件总线名称，可以使用默认值default
event_bridge_bus_name = "" 					   
# 事件总线规则名称
event_bridge_rule_name = ""			 		    
# 函数计算服务的Endpoint
fc_endpoint	= ""									   
# 事件总线过滤规则
member_account_eventbridge_filter = ""     
# 事件总线跨账号路由授权策略名称
log_account_put_events_policy = "" 			 
# 日志账号UID
log_archive_uid = ""  
# 成员账号UID
member_uid = ""  
 # 成员账号别名
member_uid_alias = "" 
member_uid_role_name = member_uid_alias + "-account-eb-role"
# 日志账号中要配置的项
# 函数计算所在的安全组ID
sec_group_id = ""
# 函数计算所在的VPC实例ID
vpc = ""
# 函数计算所在的交换机ID列表
vswitch = ["",""]
# 函数计算使用的ROLE的ARN标识，在开通函数计算时选择创建
fc_role = ""
# 函数计算服务名称
srv_name = ""
# 函数计算函数名称
fc_name = ""
# 函数计算用到的代码上传到指定的OSS Bucket
code_oss_bucket_name = ""
# 函数计算用到的代码上传到指定的OSS文件对象
code_oss_object_name = "index.py.zip"
# 函数计算配置的环境变量，包括数据库连接地址，账号密码等
mysql_endpoint = ""
mysql_port = 3306
mysql_user = ""
mysql_password = ""
mysql_dbname = ""
```
2.2 安装依赖的Python库。
`pip install -r requirement.txt`
2.3 执行
`python subscription.py`

## 执行效果
- 在指定的成员账号中完成事件总线配置，包括事件过滤规则及投递目标。
- 在日志账号中完成相应角色创建，解决跨账号事件总线投递。
- 在日志账号中完成函数计算配置

## 可能会遇到的问题
- 一定要准确配置代码里面的配置项。否则运行过程中可能会出错。
- 在配置函数计算过程中，需要事先将代码里面的index.py打包并上传到OSS里面，在代码配置中需要用到。




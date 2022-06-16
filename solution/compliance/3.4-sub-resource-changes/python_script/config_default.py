# 资源管理账号拥有资源目录权限的AK
master_account_access_key = ""
# 资源管理账号拥有资源目录权限的SK
master_account_secret_key = ""
# 资源部署所在的Region
region_id = "cn-shanghai"
# 事件总线名称，可以使用默认值default
event_bridge_bus_name = "default"
# 事件总线规则名称
event_bridge_rule_name = ""
# 函数计算服务的Endpoint
fc_endpoint = "cn-shanghai.fc.aliyuncs.com"
# 事件总线过滤规则
member_account_eventbridge_filter = "{\"source\":[\"acs.ram\"],\"data\":{\"resourceName\":[{\"prefix\":\"sg-\"}]},\"type\":[\"ram:Config:ConfigurationItemChangeNotification\"]}"
# 事件总线跨账号路由授权策略名称
log_account_put_events_policy = "AliyunEventBridgePutEventsPolicy"

# 日志账号UID
log_archive_uid = ""

# 成员账号UID
member_uid = ""

# 成员账号别名
member_uid_alias = "eb"
member_uid_role_name = member_uid_alias + "-account-eb-role"

# 日志账号中要配置的项
# 函数计算所在的安全组ID
sec_group_id = "sg-"
# 函数计算所在的VPC实例ID
vpc = "vpc-"
# 函数计算所在的交换机ID列表
vswitch = ["vsw-","vsw-"]
# 函数计算使用的ROLE的ARN标识，在开通函数计算时选择创建
fc_role = ""
# 函数计算服务名称
srv_name = ""
# 函数计算函数名称
fc_name = "eb_event_trigger"
# 函数计算用到的代码上传到指定的OSS Bucket
code_oss_bucket_name = ""
# 函数计算用到的代码上传到指定的OSS文件对象
code_oss_object_name = "index.py.zip"
# 函数计算配置的环境变量，包括数据库连接地址，账号密码等
mysql_endpoint = "s"
mysql_port = 3306
mysql_user = "root"
mysql_password = "1"
mysql_dbname = "db"


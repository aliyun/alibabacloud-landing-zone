resource "alicloud_log_alert" "dataflow_at_slb_http" {
  count             = contains(var.enabled_alerts, "dataflow.at.slb_http") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "dataflow_at_slb_http"
  alert_displayname = "LoadBalancer(SLB) HTTP Access Protocol Enabled Alert"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "账号$${account_id}下的负载均衡（SLB）实例$${instance_id}的HTTP访问协议已被开启。负载均衡应该禁止通过HTTP协议访问，只允许通过HTTPS协议访问。操作账号ID：$${ram_user_id},账号名：$${user_name},账号类型:$${user_type}。"
  }
  annotations {
    key   = "title"
    value = "负载均衡HTTP访问协议开启告警"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "event.serviceName: Slb and event.eventName: CreateLoadBalancerHTTPListener | SELECT resourceArray[num] as instance_id,  account_id, ram_user_id, arbitrary(user_type) as user_type, arbitrary(user_name) as user_name FROM (SELECT \"event.userIdentity.accountId\" as account_id, \"event.userIdentity.principalId\" as ram_user_id, case when \"event.userIdentity.type\"='root-account' then '阿里云主账号' when \"event.userIdentity.type\"='ram-user' then 'RAM用户' when \"event.userIdentity.type\"='assumed-role' then 'RAM角色' when \"event.userIdentity.type\"='system' then '阿里云服务' else \"event.userIdentity.type\" end as user_type, \"event.userIdentity.userName\" as user_name, split(\"event.resourceName\", ';') as resourceArray, array_position(split(\"event.resourceType\", ';'), 'ACS::SLB::LoadBalancer') as num FROM log) where num > 0 group by account_id, ram_user_id, instance_id"
    region         = var.project_region
    role_arn       = ""
    start          = "-30m"
    store          = var.log_store
    store_type     = "log"
    time_span_type = "Relative"
  }
  
  severity_configurations {
    eval_condition = {
      condition      = ""
      countCondition = ""
    }
    severity = 8
  }
  
  no_data_fire     = false
  no_data_severity = 6
  send_resolved    = false
  auto_annotation  = false
  
  group_configuration {
    fields = ["instance_id","account_id","ram_user_id"]
    type   = "custom"
  }

  policy_configuration {
    action_policy_id = "sls.app.actiontrail.builtin"
    alert_policy_id  = "sls.app.actiontrail.builtin"
    repeat_interval  = "2h"
  }
}
  
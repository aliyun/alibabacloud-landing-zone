resource "alicloud_log_alert" "cis_at_rds_conf_change" {
  count             = contains(var.enabled_alerts, "cis.at.rds_conf_change") ? 1 : 0
  version           = "2.0"
  project_name      = var.project_name
  alert_name        = "cis_at_rds_conf_change"
  alert_displayname = "RDS instance Configurations Change Alert"

  schedule {
    type     = "FixedRate"
    interval = "15m"
  }

  annotations {
    key   = "desc"
    value = "账号$${account_id}下的RDS实例$${db_instance_id}的配置发生变更，变更类型：$${event_name}。操作账号ID：$${ram_user_id},账号名：$${user_name},账号类型:$${user_type}。"
  }
  annotations {
    key   = "title"
    value = "RDS实例配置变更告警"
  }
  
  threshold = 1

  query_list {
    dashboard_id   = ""
    end            = "now"
    power_sql_mode = "disable"
    project        = var.project_name
    query          = "event.serviceName: Rds AND (event.eventName: ModifyHASwitchConfig OR event.eventName: ModifyDBInstanceHAConfig OR event.eventName: SwitchDBInstanceHA OR event.eventName: ModifyDBInstanceSpec OR event.eventName: MigrateSecurityIPMode OR event.eventName: ModifySecurityIps OR event.eventName: ModifyDBInstanceSSL OR event.eventName: MigrateToOtherZone OR event.eventName: UpgradeDBInstanceKernelVersion OR event.eventName: UpgradeDBInstanceEngineVersion OR event.eventName: ModifyDBInstanceMaintainTime OR event.eventName: ModifyDBInstanceAutoUpgradeMinorVersion OR event.eventName: AllocateInstancePublicConnection OR event.eventName: ModifyDBInstanceConnectionString OR event.eventName: ModifyDBInstanceNetworkExpireTime OR event.eventName: ReleaseInstancePublicConnection OR event.eventName: SwitchDBInstanceNetType OR event.eventName: ModifyDBInstanceNetworkType OR event.eventName: ModifyDBInstanceSSL OR event.eventName: ModifyDTCSecurityIpHostsForSQLServer OR event.eventName: ModifySecurityGroupConfiguration OR event.eventName: CreateBackup OR event.eventName: ModifyBackupPolicy OR event.eventName: DeleteBackup OR event.eventName: CreateDdrInstance OR event.eventName: ModifyInstanceCrossBackupPolicy OR event.eventName :ModifySQLCollectorPolicy OR event.eventName:ModifyDBInstanceTDE ) | SELECT COALESCE(account_id, recipientAccountId) as account_id, resourceArray[num] as db_instance_id, event_name, ram_user_id, arbitrary(user_type) as user_type, arbitrary(user_name) as user_name  FROM (SELECT \"event.userIdentity.accountId\" as account_id, \"event.recipientAccountId\" as recipientAccountId, split(\"event.resourceName\", ';') as resourceArray, array_position(split(\"event.resourceType\", ';'), 'ACS::RDS::DBInstance') as num, \"event.eventName\" as event_name, \"event.userIdentity.principalId\" as ram_user_id, case when \"event.userIdentity.type\"='root-account' then '阿里云主账号' when \"event.userIdentity.type\"='ram-user' then 'RAM用户' when \"event.userIdentity.type\"='assumed-role' then 'RAM角色' when \"event.userIdentity.type\"='system' then '阿里云服务' else \"event.userIdentity.type\" end as user_type, \"event.userIdentity.userName\" as user_name FROM log) where num > 0 group by account_id,ram_user_id, db_instance_id, event_name limit 10000"
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
    severity = 4
  }
  
  no_data_fire     = false
  no_data_severity = 6
  send_resolved    = false
  auto_annotation  = false
  
  group_configuration {
    fields = ["account_id","ram_user_id","db_instance_id","event_name"]
    type   = "custom"
  }

  policy_configuration {
    action_policy_id = "sls.app.actiontrail.builtin"
    alert_policy_id  = "sls.app.actiontrail.builtin"
    repeat_interval  = "2h"
  }
}
  
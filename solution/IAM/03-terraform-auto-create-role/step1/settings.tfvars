# AK，需要手动填写AK和AK Secret
access_key = ""
secret_key = ""
region = "cn-hangzhou"

# 需要创建的 RAM 角色列表，可根据需要修改
ram_roles = {
  # 角色名称，需要唯一
  "BillingAdmin": {
    # 角色描述，可以不填
    description = ""
    # 需要授权的权限列表
    policies = [
      "AliyunBSSFullAccess",
      "AliyunFinanceConsoleFullAccess"
    ]
  },
  "CloudAdmin": {
    description = ""
    policies = [
      "AdministratorAccess"
    ]
  },
  "NetworkAdmin": {
    description = ""
    policies = [
      "AliyunVPCFullAccess",
      "AliyunNATGatewayFullAccess",
      "AliyunEIPFullAccess",
      "AliyunCENFullAccess",
      "AliyunVPNGatewayFullAccess",
      "AliyunExpressConnectFullAccess",
      "AliyunCommonBandwidthPackageFullAccess",
      "AliyunSmartAccessGatewayFullAccess",
      "AliyunGlobalAccelerationFullAccess",
      "AliyunECSNetworkInterfaceManagementAccess",
      "AliyunDNSFullAccess",
      "AliyunYundunNewBGPAntiDDoSServicePROFullAccess"
    ]
  },
  "DBAdmin": {
    description = ""
    policies = [
      "AliyunRDSFullAccess",
      "AliyunDRDSFullAccess",
      "AliyunOCSFullAccess",
      "AliyunPolardbFullAccess",
      "AliyunADBFullAccess",
      "AliyunDTSFullAccess",
      "AliyunMongoDBFullAccess",
      "AliyunPetaDataFullAccess",
      "AliyunGPDBFullAccess",
      "AliyunHBaseFullAccess",
      "AliyunYundunDbAuditFullAccess",
      "AliyunHiTSDBFullAccess",
      "AliyunDBSFullAccess",
      "AliyunHDMFullAccess",
      "AliyunGDBFullAccess",
      "AliyunOceanBaseFullAccess",
      "AliyunCassandraFullAccess",
      "AliyunClickHouseFullAccess",
      "AliyunDLAFullAccess"
    ]
  },
  "SLBAdmin": {
    description = ""
    policies = [
      "AliyunSLBFullAccess",
      "AliyunEIPFullAccess",
      "AliyunECSNetworkInterfaceManagementAccess"
    ]
  },
  "CDNAdmin": {
    description = ""
    policies = [
      "AliyunCDNFullAccess"
    ]
  },
  "MonitorAdmin": {
    description = ""
    policies = [
      "AliyunCloudMonitorFullAccess"
    ]
  },
  "MiddlewareAdmin": {
    description = ""
    policies = [
      "AliyunKvstoreFullAccess",
      "AliyunMQFullAccess",
      "AliyunElasticsearchFullAccess"
    ]
  }
}

access_key=""
secret_key=""
region="cn-shanghai"

basic_settings = {
  # 资源目录设置
  resource_directory = {
    # Core 目录的名称
    core_directory_name = "Core"
    # Applications 目录的名称
    applications_directory_name = "Applications"
  }
  # admin 子账号名称
  admin_sub_account_name = "admin"
  # SharedServices 账号名称
  shared_services_account_name = "SharedServices"
  # SharedServices 账号下的 RAM 角色
  shared_services_account_roles = [
    {
      role_name = "EnterpriseIdP-CloudAdmin"
      idp_display_name = "Ali-CloudAdmin-SharedServices"
      idp_group_name = "Ali-CloudAdmin-SharedServices"
      policies = [
        "AdministratorAccess"
      ]
    },
    {
      role_name = "EnterpriseIdP-NetworkAdmin"
      idp_display_name = "Ali-NetworkAdmin-SharedServices"
      idp_group_name = "Ali-NetworkAdmin-SharedServices"
      policies = [
        "AliyunVPCFullAccess",
        "AliyunNATGatewayFullAccess",
        "AliyunEIPFullAccess",
        "AliyunCENFullAccess",
        "AliyunVPNGatewayFullAccess",
        "AliyunSLBFullAccess",
        "AliyunExpressConnectFullAccess",
        "AliyunCommonBandwidthPackageFullAccess",
        "AliyunSmartAccessGatewayFullAccess",
        "AliyunGlobalAccelerationFullAccess",
        "AliyunECSNetworkInterfaceManagementAccess",
        "AliyunDNSFullAccess",
        "AliyunCDNFullAccess",
        "AliyunYundunNewBGPAntiDDoSServicePROFullAccess"
      ]
    },
    {
      role_name = "EnterpriseIdP-DBAdmin"
      idp_display_name = "Ali-DBAdmin-SharedServices"
      idp_group_name = "Ali-DBAdmin-SharedServices"
      policies = [
        "AliyunRDSFullAccess",
        "AliyunDRDSFullAccess",
        "AliyunKvstoreFullAccess",
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
    {
      role_name = "EnterpriseIdP-MonitorAdmin"
      idp_display_name = "Ali-MonitorAdmin-SharedServices"
      idp_group_name = "Ali-MonitorAdmin-SharedServices"
      policies = [
        "AliyunCloudMonitorFullAccess"
      ]
    },
    {
      role_name = "EnterpriseIdP-SecurityAdmin"
      idp_display_name = "Ali-SecurityAdmin-SharedServices"
      idp_group_name = "Ali-SecurityAdmin-SharedServices"
      policies = [
        "AliyunYundunFullAccess"
      ]
    },
    {
      role_name = "EnterpriseIdP-SecurityAuditor"
      idp_display_name = "Ali-SecurityAuditor-SharedServices"
      idp_group_name = "Ali-SecurityAuditor-SharedServices"
      policies = [
        "AliyunYundunHighReadOnlyAccess",
        "AliyunYundunAegisReadOnlyAccess",
        "AliyunYundunSASReadOnlyAccess",
        "AliyunYundunBastionHostReadOnlyAccess",
        "AliyunYundunCertReadOnlyAccess",
        "AliyunYundunDDosReadOnlyAccess",
        "AliyunYundunWAFReadOnlyAccess",
        "AliyunYundunDbAuditReadOnlyAccess",
        "AliyunYundunCloudFirewallReadOnlyAccess",
        "AliyunYundunIdaasReadOnlyAccess"
      ]
    },
    {
      role_name = "EnterpriseIdP-LogAdmin"
      idp_display_name = "Ali-LogAdmin-SharedServices"
      idp_group_name = "Ali-LogAdmin-SharedServices"
      policies = [
        "AliyunLogFullAccess"
      ]
    },
    {
      role_name = "EnterpriseIdP-LogViewer"
      idp_display_name = "Ali-LogViewer-SharedServices"
      idp_group_name = "Ali-LogViewer-SharedServices"
      policies = [
        "AliyunLogReadOnlyAccess"
      ]
    },
    # {
    #   role_name = ""
    #   idp_display_name = "Ali-CommonUser-SharedServices"
    #   idp_group_name = "Ali-CommonUser-SharedServices"
    #   policies = []
    # }
  ]

  governance = {
    # 用于存放审计日志的 OSS Bucket 名称，全局唯一，推荐修改
    bucket_enterprise_audit_logs = format("landingzone-enterprise-audit-logs-%s", uuid())
    # 创建操作审计的跟踪名称，全局唯一，推荐修改
    trail_enterprise_audit_logs = format("enterprise-audit-logs", uuid())

    mns={
        topic_name = "notice-enterprise-logs-received"
        message_size = 65536
        logging_enable = true
    }
  }
}

network_settings = {
  # 是否创建堡垒机
  bastion_host_enabled = false
  # Shared Services VPC 配置信息
  vpc_shared_services = {
    vpc_name = "vpc-sharedservices"
    cidr_block = "10.36.10.0/24"
    vswitches = [
      {
        vswitch_name = "vsw-sharedservices-1"
        cidr_block = "10.36.10.0/26"
        zone = "cn-shanghai-f"
      }
    ]
  }
  # DMZ VPC 配置信息
  vpc_dmz = {
    vpc_name = "vpc-dmz"
    cidr_block = "10.36.11.0/24"
    vswitches = [
      {
        vswitch_name = "vsw-dmz-1"
        cidr_block = "10.36.11.0/26"
        zone = "cn-shanghai-f"
      }
    ]
    # 是否在 DMZ VPC 内创建 NAT Gateway
    natgateway_enabled = false
    # NAT Gateway 名称
    natgateway_name = "nat-dmz"
    # NAT Gateway 绑定的 EIP 带宽
    eip_bandwidth = "10"
    # NAT Gateway 绑定的 EIP 付费方式
    eip_internet_charge_type = "PayByBandwidth"
    # 是否创建共享带宽包
    common_bandwidth_package_enabled = false
    # 共享带宽包带宽
    common_bandwidth_package_bandwidth = "100"
    # 共享带宽包付费方式
    common_bandwidth_package_internet_charge_type = "PayByBandwidth"
  }
  # Production VPC 配置信息
  vpc_production = {
    cidr_block = "10.34.64.0/20"
    vpc_name = "production"
  }
  # Non-Production VPC 配置信息
  vpc_non_production = {
    cidr_block = "10.34.96.0/22"
    vpc_name = "non-production"
  }
}
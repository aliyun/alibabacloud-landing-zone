# Please modify according to the actual situation

# Account information
management_account_id     = "114xxxx592"
payer_account_id          = "114xxxx592"
log_account_id            = "104xxxx656"
shared_service_account_id = "186xxxx635"
core_folder_id            = "fd-xxxxcMA"
applications_folder_id    = "fd-xxxxw6j"

security_display_name        = "安全账号3"
security_account_name_prefix = "security3"

ops_display_name        = "运维账号3"
ops_account_name_prefix = "operation3"

dev_display_name        = "开发测试账号3"
dev_account_name_prefix = "dev3"

prod_display_name        = "生产账号3"
prod_account_name_prefix = "prod3"

# SSO Provider Name
sso_provider_name        = "idp"
sso_provider_description = "Created with Terraform automation scripts."

encodedsaml_metadata_document = "yourEncodedSAMLDocumentData"


# Compliance
# The steps to enable the compliance package are as follows：
# 1. By the following template IDs，call the ListCompliancePackTemplates interface to query the list of compliance package templates.
# @see https://help.aliyun.com/document_detail/285530.html
# 2. According to the following configuration format，build the configuration based on the returned rule result.
# Pay attention to modifying the rule parameters.
# @see https://registry.terraform.io/providers/aliyun/alicloud/latest/docs/resources/config_aggregate_compliance_pack

# The built-in template IDs are as follows:
# "OSS合规管理最佳实践"："ct-a5edff4e06a3004a5e15"
# "网络合规管理最佳实践"："ct-d254ff4e06a300cfc654"
# "账号权限合规管理最佳实践"："ct-d264ff4e06a300a9c2d0"
# "等保三级预检合规包"："ct-5f26ff4e06a300c49609"
# "CIS网络安全框架检查合规包"："ct-5f99ff4e06a3006c54d2"
# "数据库合规管理最佳实践"："ct-a292ff4e06a300b2db8b"
# "云治理中心合规实践"："ct-a292ff4e06a300b2db8c"
# "ECS合规管理最佳实践"："ct-3d20ff4e06a30027f76e"
# "RMiT金融标准检查合规包"："ct-81ceff4e06a3008583ca"
# "安全组最佳实践"："ct-484cff4e06a300621b5b"
# "OceanBase最佳实践"："ct-484cff4e06a300621b5c"
# "资源稳定性最佳实践"："ct-484cff4e06a300621b5e"

# The following configuration is just an example. Please modify the rule parameters!
# In particular, include the allowEmptyReferer parameters and
# allowEmptyReferer parameters in the following configuration！
config_compliance_packs = [
  {
    config_compliance_pack_name        = "OSS合规管理"
    config_compliance_pack_description = "持续检测云上存储的加密、备份、防盗、访问控制的合规性，避免数据泄露等数据安全风险。"
    config_compliance_pack_template_id = "ct-a5edff4e06a3004a5e15"
    config_compliance_pack_risk_level  = 1
    config_compliance_rules            = [
      {
        rule_name            = "OSS存储空间Referer在指定的防盗链白名单中"
        rule_description     = "OSS存储空间开启防盗链并且Referer在指定白名单中，视为“合规”。"
        rule_identifier      = "oss-bucket-referer-limit"
        parameters           = [
          {
            name  = "allowEmptyReferer"
            value = "true"
          }, {
            name  = "allowReferers"
            value = "www.landingzone.cc"
          }
        ]
        resource_types_scope = ["ACS::OSS::Bucket"]
        tag_key_scope        = null
        tag_value_scope      = null
      },
      {
        rule_name            = "OSS存储空间ACL禁止公共读"
        rule_description     = "OSS存储空间的ACL策略禁止公共读，视为“合规”。"
        rule_identifier      = "oss-bucket-public-read-prohibited"
        parameters           = []
        resource_types_scope = ["ACS::OSS::Bucket"]
        tag_key_scope        = null
        tag_value_scope      = null
      }, {
        rule_name            = "OSS存储空间ACL禁止公共读写"
        rule_description     = "OSS存储空间的ACL策略禁止公共读写，视为“合规”。"
        rule_identifier      = "oss-bucket-public-write-prohibited"
        parameters           = []
        resource_types_scope = ["ACS::OSS::Bucket"]
        tag_key_scope        = null
        tag_value_scope      = null
      }, {
        rule_name            = "OSS存储空间开启服务端加密"
        rule_description     = "OSS存储空间开启服务端OSS完全托管加密，视为“合规”。"
        rule_identifier      = "oss-bucket-server-side-encryption-enabled"
        parameters           = []
        resource_types_scope = ["ACS::OSS::Bucket"]
        tag_key_scope        = null
        tag_value_scope      = null
      }, {
        rule_name            = "OSS存储空间开启同城冗余存储"
        rule_description     = "OSS存储空间开启同城冗余存储，视为“合规”。"
        rule_identifier      = "oss-zrs-enabled"
        parameters           = []
        resource_types_scope = ["ACS::OSS::Bucket"]
        tag_key_scope        = null
        tag_value_scope      = null
      }, {
        rule_name            = "OSS存储空间开启日志存储"
        rule_description     = "OSS存储空间的日志管理中开启日志存储，视为“合规”。"
        rule_identifier      = "oss-bucket-logging-enabled"
        parameters           = []
        resource_types_scope = ["ACS::OSS::Bucket"]
        tag_key_scope        = null
        tag_value_scope      = null
      }, {
        rule_name            = "OSS存储空间开启版本控制"
        rule_description     = "OSS存储空间开启版本控制，视为“合规”。"
        rule_identifier      = "oss-bucket-versioning-enabled"
        parameters           = []
        resource_types_scope = ["ACS::OSS::Bucket"]
        tag_key_scope        = null
        tag_value_scope      = null
      }
    ]
  }
]


# ECS & ALB
security_group_name                = "sg-lz-tf"
security_group_desc                = "sg-lz-tf"
ecs_instance_password              = "Ll1234qaz"
dmz_vpc_ecs_instance_deploy_config = [
  {
    instance_name = "ecs-dmz-sh-1"
    host_name     = "ecs-dmz-sh-1"
    description   = "ecs-dmz-sh-1"
  }, {
    instance_name = "ecs-dmz-sh-2"
    host_name     = "ecs-dmz-sh-2"
    description   = "ecs-dmz-sh-2"
  }
]

dev_vpc_ecs_instance_deploy_config = [
  {
    instance_name = "ecs-dev-sh-1"
    host_name     = "ecs-dev-sh-1"
    description   = "ecs-dev-sh-1"
  }, {
    instance_name = "ecs-dev-sh-2"
    host_name     = "ecs-dev-sh-2"
    description   = "ecs-dev-sh-2"
  }
]

# ECS instance spec
ecs_instance_spec = {
  instance_type              = "ecs.t5-lc1m1.small"
  system_disk_category       = "cloud_efficiency"
  image_id                   = "centos_8_5_x64_20G_alibase_20220428.vhd"
  instance_charge_type       = "PostPaid"
  period_unit                = "Month"
  period                     = 1
  internet_max_bandwidth_out = 0
  tags                       = { createdBy : "Terraform" }
  volume_tags                = { createdBy : "Terraform" }
}

# ALB instance info
dmz_vpc_alb_instance_name = "alb-tf-default"
dev_vpc_alb_instance_name = "alb-tf-default"

# ALB instance spec
alb_instance_spec = {
  protocol               = "HTTP"
  address_type           = "Internet"
  address_allocated_mode = "Fixed"
  load_balancer_name     = "alb-tf-default"
  load_balancer_edition  = "Basic"
  tags                   = { createdBy : "Terraform" }
}

# ALB listener
alb_listener_description = "createdByTerraform"

# ALB server group
server_group_config = {
  server_group_name = "server-group-tf"
  protocol          = "HTTP"
  tags              = { createdBy : "Terraform" }

  health_check_protocol     = "HTTP"
  health_check_connect_port = "80"
  health_check_enabled      = true
  health_check_codes        = ["http_2xx", "http_3xx", "http_4xx"]
  health_check_http_version = "HTTP1.1"
  health_check_interval     = "2"
  health_check_method       = "GET"
  health_check_path         = "/hello_landing_zone"
  health_check_timeout      = 5
  healthy_threshold         = 3
  unhealthy_threshold       = 3

  sticky_session_enabled = false
  cookie                 = null
  cookie_timeout         = 1000
  sticky_session_type    = "Insert"

  port   = 80
  weight = 100
}

# security group
security_group_rule = [
  {
    type        = "ingress"
    ip_protocol = "tcp"
    nic_type    = "intranet"
    policy      = "accept"
    port_range  = "80/80"
    priority    = 1
    cidr_ip     = "0.0.0.0/0"
  }, {
    type        = "ingress"
    ip_protocol = "tcp"
    nic_type    = "intranet"
    policy      = "accept"
    port_range  = "22/22"
    priority    = 1
    cidr_ip     = "0.0.0.0/0"
  }, {
    type        = "ingress"
    ip_protocol = "tcp"
    nic_type    = "intranet"
    policy      = "accept"
    port_range  = "443/443"
    priority    = 1
    cidr_ip     = "0.0.0.0/0"
  }, {
    type        = "ingress"
    ip_protocol = "tcp"
    nic_type    = "intranet"
    policy      = "accept"
    port_range  = "3389/3389"
    priority    = 1
    cidr_ip     = "0.0.0.0/0"
  }, {
    type        = "ingress"
    ip_protocol = "icmp"
    nic_type    = "intranet"
    policy      = "accept"
    port_range  = "-1/-1"
    priority    = 1
    cidr_ip     = "0.0.0.0/0"
  }
]


# Network
cen_instance_name = "Terraform-CEN"
cen_instance_desc = "Created by Terraform"
cen_instance_tags = {
  "Environment" = "shared"
  "Department"  = "ops"
}

# contains the cidr addresses of all vpcs
all_vpc_cidr = "10.0.0.0/8"

dmz_egress_nat_gateway_name = "nat-gateway-dmz-egress"
dmz_egress_eip_name         = "eip--dmz-egress"

shared_service_account_vpc_config = {
  "region"   = "cn-shanghai"
  "vpc_name" = "vpc-sh-dmz"
  "vpc_desc" = "Demilitarized Zone"
  "vpc_cidr" = "10.0.0.0/16"
  "vpc_tags" = {
    "Environment" = "shared"
    "Department"  = "ops"
  }
  "vswitch"  = [
    {
      "vswitch_name" = "vsw-sh-dmz-f-1"
      "vswitch_desc" = "vsw-sh-dmz-f-1"
      "vswitch_cidr" = "10.0.4.0/22"
      "zone_id"      = "cn-shanghai-f"
      "vswitch_tags" = {
        "Environment" = "shared"
        "Department"  = "ops"
      }
    }, {
      "vswitch_name" = "vsw-sh-dmz-g-1"
      "vswitch_desc" = "vsw-sh-dmz-g-1"
      "vswitch_cidr" = "10.0.8.0/22"
      "zone_id"      = "cn-shanghai-g"
      "vswitch_tags" = {
        "Environment" = "shared"
        "Department"  = "ops"
      }
    }
  ]
}

dev_account_vpc_config = {
  "region"   = "cn-shanghai"
  "vpc_name" = "vpc-sh-development"
  "vpc_desc" = "Development VPC"
  "vpc_cidr" = "10.1.0.0/16"
  "vpc_tags" = {
    "Environment" = "dev"
    "Department"  = "department1"
  }
  "vswitch"  = [
    {
      "vswitch_name" = "vsw-sh-dev-f-1"
      "vswitch_desc" = "vsw-sh-dev-f-1"
      "vswitch_cidr" = "10.1.4.0/22"
      "zone_id"      = "cn-shanghai-f"
      "vswitch_tags" = {
        "Environment" = "dev"
        "Department"  = "department1"
      }
    }, {
      "vswitch_name" = "vsw-sh-dev-g-1"
      "vswitch_desc" = "vsw-sh-dev-g-1"
      "vswitch_cidr" = "10.1.8.0/22"
      "zone_id"      = "cn-shanghai-g"
      "vswitch_tags" = {
        "Environment" = "dev"
        "Department"  = "department1"
      }
    }
  ]
}

prod_account_vpc_config = {
  "region"   = "cn-shanghai"
  "vpc_name" = "vpc-sh-production"
  "vpc_desc" = "Production VPC"
  "vpc_cidr" = "10.2.0.0/16"
  "vpc_tags" = {
    "Environment" = "prod"
    "Department"  = "department1"
  }
  "vswitch"  = [
    {
      "vswitch_name" = "vsw-sh-prod-f-1"
      "vswitch_desc" = "vsw-sh-prod-f-1"
      "vswitch_cidr" = "10.2.4.0/22"
      "zone_id"      = "cn-shanghai-f"
      "vswitch_tags" = {
        "Environment" = "prod"
        "Department"  = "department1"
      }
    }, {
      "vswitch_name" = "vsw-sh-prod-g-1"
      "vswitch_desc" = "vsw-sh-prod-g-1"
      "vswitch_cidr" = "10.2.8.0/22"
      "zone_id"      = "cn-shanghai-g"
      "vswitch_tags" = {
        "Environment" = "prod"
        "Department"  = "department1"
      }
    }
  ]
}


ops_account_vpc_config = {
  "region"   = "cn-shanghai"
  "vpc_name" = "vpc-sh-management"
  "vpc_desc" = "Management VPC"
  "vpc_cidr" = "10.3.0.0/16"
  "vpc_tags" = {
    "Environment" = "ops"
    "Department"  = "ops"
  }
  "vswitch"  = [
    {
      "vswitch_name" = "vsw-sh-management-f-1"
      "vswitch_desc" = "vsw-sh-management-f-1"
      "vswitch_cidr" = "10.3.4.0/22"
      "zone_id"      = "cn-shanghai-f"
      "vswitch_tags" = {
        "Environment" = "ops"
        "Department"  = "ops"
      }
    }, {
      "vswitch_name" = "vsw-sh-management-g-1"
      "vswitch_desc" = "vsw-sh-management-g-1"
      "vswitch_cidr" = "10.3.8.0/22"
      "zone_id"      = "cn-shanghai-g"
      "vswitch_tags" = {
        "Environment" = "ops"
        "Department"  = "ops"
      }
    }
  ]
}

# Security
# @see https://registry.terraform.io/providers/aliyun/alicloud/latest/docs/resources/waf_instance
waf_instance_spec = {
  big_screen           = "0"
  exclusive_ip_package = "1"
  ext_bandwidth        = "50"
  ext_domain_package   = "1"
  package_code         = "version_3"
  prefessional_service = "false"
  subscription_type    = "Subscription"
  period               = 1
  waf_log              = "false"
  log_storage          = "3"
  log_time             = "180"
}

waf_domain_config = {
  is_access_product = "On"
  http2_port        = [443]
  http_port         = [80]
  https_port        = [443]
  http_to_user_ip   = "Off"
  https_redirect    = "Off"
  load_balancing    = "IpHash"
}

# @see https://registry.terraform.io/providers/aliyun/alicloud/latest/docs/resources/ddoscoo_instance
ddos_bgp_instance_spec = {
  name              = "createByTerraform"
  bandwidth         = "30"
  base_bandwidth    = "30"
  service_bandwidth = "100"
  port_count        = "50"
  domain_count      = "50"
  period            = "1"
  product_type      = "ddoscoo"
}

ddos_domain_https_ext   = "{\"Http2\":0,\"Http2https\":0,\"Https2http\":0}"
ddos_domain_proxy_types = [
  {
    proxy_ports = [80]
    proxy_type  = "http"
  }
]

# @see https://registry.terraform.io/providers/aliyun/alicloud/latest/docs/resources/cloud_firewall_instance
cfw_instance_spec = {
  payment_type    = "Subscription"
  spec            = "ultimate_version"
  ip_number       = 400
  band_width      = 200
  cfw_log         = false
  cfw_log_storage = 5000
  cfw_service     = false
  fw_vpc_number   = 5
  period          = 6
}

cfw_control_policy = [
  {
    application_name = "HTTP"
    acl_action       = "accept"
    description      = "createdByTerraform"
    destination_type = "net"
    destination      = "0.0.0.0/0"
    dest_port        = "80/80"
    dest_port_type   = "port"
    direction        = "out"
    proto            = "TCP"
    source           = "0.0.0.0/0"
    source_type      = "net"
    order            = 1
  }, {
    application_name = "HTTPS"
    acl_action       = "accept"
    description      = "createdByTerraform"
    destination_type = "net"
    destination      = "0.0.0.0/0"
    dest_port        = "443/443"
    dest_port_type   = "port"
    direction        = "out"
    proto            = "TCP"
    source           = "0.0.0.0/0"
    source_type      = "net"
    order            = 2
  }
]


# Identity and permissions
ram_user_initial_pwd         = "pop$#cem%$z19D"
management_account_ram_users = [
  {
    "name"                 = "Emergency"
    "description"          = "Emergency user"
    "enable_console_login" = true
    "enable_api_access"    = false
    "system_policy"        = ["AdministratorAccess"]
  }
]

management_account_ram_roles = [
  {
    "name"          = "SystemAdmin"
    "description"   = "Administrator role"
    "system_policy" = ["AdministratorAccess"]
  },
  {
    "name"          = "ResourceDirectoryAdmin"
    "description"   = "Administrator role for Resource Directory"
    "system_policy" = ["AliyunResourceDirectoryFullAccess"]
  },
  {
    "name"          = "BillingAdmin"
    "description"   = "Administrator role for billing"
    "system_policy" = ["AliyunBSSFullAccess"]
  }
]


log_account_ram_users = [
  {
    "name"                 = "ProgrammaticReadOnlyUser"
    "description"          = "Programmatic Read Only User"
    "enable_console_login" = false
    "enable_api_access"    = true
    "system_policy"        = ["ReadOnlyAccess"]
  }, {
    "name"                 = "ProgrammaticUser"
    "description"          = "Programmatic User"
    "enable_console_login" = false
    "enable_api_access"    = true
    "system_policy"        = ["AliyunOSSFullAccess", "AliyunLogFullAccess"]
  }
]

log_account_ram_roles = [
  {
    "name"          = "SystemAdmin"
    "description"   = "Administrator role"
    "system_policy" = ["AdministratorAccess"]
  },
  {
    "name"          = "AuditAdmin"
    "description"   = "AuditAdmin role"
    "system_policy" = [
      "AliyunActionTrailFullAccess", "AliyunConfigFullAccess", "AliyunOSSFullAccess", "AliyunLogFullAccess"
    ]
  },
  {
    "name"          = "LogAdmin"
    "description"   = "Administrator role for log"
    "system_policy" = ["AliyunOSSFullAccess", "AliyunLogFullAccess"]
  },
  {
    "name"          = "ReadOnly"
    "description"   = "Read only role"
    "system_policy" = ["ReadOnlyAccess"]
  }
]


shared_service_account_ram_users = [
  {
    "name"                 = "CloudAdmin"
    "description"          = "CloudAdmin user"
    "enable_console_login" = true
    "enable_api_access"    = false
    "system_policy"        = ["AdministratorAccess"]
  }, {
    "name"                 = "Emergency"
    "description"          = "Emergency user"
    "enable_console_login" = true
    "enable_api_access"    = false
    "system_policy"        = ["AdministratorAccess"]
  }, {
    "name"                 = "ProgrammaticUser"
    "description"          = "Programmatic user"
    "enable_console_login" = false
    "enable_api_access"    = true
    "system_policy"        = ["AdministratorAccess"]
  }
]

shared_service_account_ram_roles = [
  {
    "name"          = "SystemAdmin"
    "description"   = "Administrator role"
    "system_policy" = ["AdministratorAccess"]
  },
  {
    "name"          = "RAMAdmin"
    "description"   = "Administrator role for RAM"
    "system_policy" = ["AliyunRAMFullAccess"]
  },
  {
    "name"          = "NetworkAdmin"
    "description"   = "Administrator role for network"
    "system_policy" = [
      "AliyunVPCFullAccess", "AliyunNATGatewayFullAccess", "AliyunSLBFullAccess", "AliyunCENFullAccess",
      "AliyunEIPFullAccess", "AliyunSmartAccessGatewayFullAccess", "AliyunVPNGatewayFullAccess",
      "AliyunExpressConnectFullAccess", "AliyunBSSFullAccess"
    ]
  },
  {
    "name"          = "SecurityAdmin"
    "description"   = "Administrator role for security"
    "system_policy" = [
      "AliyunYundunCloudFirewallFullAccess", "AliyunCSASFullAccess", "AliyunYundunSASFullAccess",
      "AliyunYundunAntiDDoSPremiumFullAccess",
      "AliyunYundunDDoSRewardsFullAccess", "AliyunYundunNewBGPAntiDDoSServicePROFullAccess",
      "AliyunYundunAntiDDoSBagFullAccess",
      "AliyunYundunDDosFullAccess", "AliyunYundunWAFFullAccess", "AliyunBSSFullAccess"
    ]
  },
  {
    "name"          = "ReadOnly"
    "description"   = "Read only role"
    "system_policy" = ["ReadOnlyAccess"]
  }
]

security_account_ram_users = [
]


security_account_ram_roles = [
  {
    "name"          = "SystemAdmin"
    "description"   = "Administrator role"
    "system_policy" = ["AdministratorAccess"]
  },
  {
    "name"          = "SecurityAdmin"
    "description"   = "Administrator role for Resource Directory"
    "system_policy" = [
      "AliyunYundunCloudFirewallFullAccess", "AliyunCSASFullAccess", "AliyunYundunSASFullAccess",
      "AliyunYundunAntiDDoSPremiumFullAccess",
      "AliyunYundunDDoSRewardsFullAccess", "AliyunYundunNewBGPAntiDDoSServicePROFullAccess",
      "AliyunYundunAntiDDoSBagFullAccess",
      "AliyunYundunDDosFullAccess", "AliyunYundunWAFFullAccess", "AliyunBSSFullAccess"
    ]
  }
]

ops_account_ram_users = [
  {
    "name"                 = "CloudAdmin"
    "description"          = "CloudAdmin user"
    "enable_console_login" = true
    "enable_api_access"    = false
    "system_policy"        = ["AdministratorAccess"]
  }, {
    "name"                 = "Emergency"
    "description"          = "Emergency user"
    "enable_console_login" = true
    "enable_api_access"    = false
    "system_policy"        = ["AdministratorAccess"]
  }, {
    "name"                 = "ProgrammaticUser"
    "description"          = "Programmatic user"
    "enable_console_login" = false
    "enable_api_access"    = true
    "system_policy"        = ["AdministratorAccess"]
  }
]


ops_account_ram_roles = [
  {
    "name"          = "SystemAdmin"
    "description"   = "Administrator role"
    "system_policy" = ["AdministratorAccess"]
  },
  {
    "name"          = "RAMAdmin"
    "description"   = "Administrator role for RAM"
    "system_policy" = ["AliyunRAMFullAccess"]
  },
  {
    "name"          = "NetworkAdmin"
    "description"   = "Administrator role for network"
    "system_policy" = [
      "AliyunVPCFullAccess", "AliyunNATGatewayFullAccess", "AliyunSLBFullAccess", "AliyunCENFullAccess",
      "AliyunEIPFullAccess", "AliyunSmartAccessGatewayFullAccess", "AliyunVPNGatewayFullAccess",
      "AliyunExpressConnectFullAccess", "AliyunBSSFullAccess"
    ]
  },
  {
    "name"          = "SecurityAdmin"
    "description"   = "Administrator role for security"
    "system_policy" = [
      "AliyunYundunCloudFirewallFullAccess", "AliyunCSASFullAccess", "AliyunYundunSASFullAccess",
      "AliyunYundunAntiDDoSPremiumFullAccess",
      "AliyunYundunDDoSRewardsFullAccess", "AliyunYundunNewBGPAntiDDoSServicePROFullAccess",
      "AliyunYundunAntiDDoSBagFullAccess",
      "AliyunYundunDDosFullAccess", "AliyunYundunWAFFullAccess", "AliyunBSSFullAccess"
    ]
  },
  {
    "name"          = "ReadOnly"
    "description"   = "Read only role"
    "system_policy" = ["ReadOnlyAccess"]
  }
]

dev_account_ram_users = [
]


dev_account_ram_roles = [
  {
    "name"          = "SystemAdmin"
    "description"   = "Administrator role"
    "system_policy" = ["AdministratorAccess"]
  },
  {
    "name"          = "RAMAdmin"
    "description"   = "Administrator role for Resource Directory"
    "system_policy" = ["AliyunRAMFullAccess"]
  },
  {
    "name"          = "NetworkAdmin"
    "description"   = "Administrator role for billing"
    "system_policy" = [
      "AliyunVPCFullAccess", "AliyunNATGatewayFullAccess", "AliyunSLBFullAccess", "AliyunCENFullAccess",
      "AliyunEIPFullAccess", "AliyunSmartAccessGatewayFullAccess", "AliyunVPNGatewayFullAccess",
      "AliyunExpressConnectFullAccess", "AliyunBSSFullAccess"
    ]
  },
  {
    "name"          = "SecurityAdmin"
    "description"   = "Administrator role for billing"
    "system_policy" = [
      "AliyunYundunCloudFirewallFullAccess", "AliyunCSASFullAccess", "AliyunYundunSASFullAccess",
      "AliyunYundunAntiDDoSPremiumFullAccess",
      "AliyunYundunDDoSRewardsFullAccess", "AliyunYundunNewBGPAntiDDoSServicePROFullAccess",
      "AliyunYundunAntiDDoSBagFullAccess",
      "AliyunYundunDDosFullAccess", "AliyunYundunWAFFullAccess", "AliyunBSSFullAccess"
    ]
  },
  {
    "name"          = "DBAdmin"
    "description"   = "Administrator role for database"
    "system_policy" = [
      "AliyunMongoDBFullAccess", "AliyunGPDBFullAccess", "AliyunADBFullAccess", "AliyunGDBFullAccess",
      "AliyunPolardbFullAccess", "AliyunDBSFullAccess", "AliyunHiTSDBFullAccess", "AliyunBSSFullAccess"
    ]
  },
  {
    "name"          = "BigdataAdmin"
    "description"   = "Administrator role for bigdata"
    "system_policy" = [
      "AliyunDataWorksFullAccess", "AliyunDataHubFullAccess", "AliyunDataVFullAccess", "AliyunDLAFullAccess",
      "AliyunEMRFullAccess", "AliyunPAIEASFullAccess", "AliyunElasticsearchFullAccess", "AliyunBSSFullAccess"
    ]
  },
  {
    "name"          = "ReadOnly"
    "description"   = "ReadOnly"
    "system_policy" = ["ReadOnlyAccess"]
  },
  {
    "name"          = "ContainerAdmin"
    "description"   = "Administrator role for container"
    "system_policy" = ["AliyunContainerRegistryFullAccess", "AliyunCSFullAccess", "AliyunBSSFullAccess"]
  },
  {
    "name"          = "ContainerReadOnly"
    "description"   = "Administrator role for container"
    "system_policy" = ["AliyunCSReadOnlyAccess", "AliyunContainerRegistryFullAccess"]
  }
]

prod_account_ram_users = [
]


prod_account_ram_roles = [
  {
    "name"          = "SystemAdmin"
    "description"   = "Administrator role"
    "system_policy" = ["AdministratorAccess"]
  },
  {
    "name"          = "RAMAdmin"
    "description"   = "Administrator role for Resource Directory"
    "system_policy" = ["AliyunRAMFullAccess"]
  },
  {
    "name"          = "NetworkAdmin"
    "description"   = "Administrator role for billing"
    "system_policy" = [
      "AliyunVPCFullAccess", "AliyunNATGatewayFullAccess", "AliyunSLBFullAccess", "AliyunCENFullAccess",
      "AliyunEIPFullAccess", "AliyunSmartAccessGatewayFullAccess", "AliyunVPNGatewayFullAccess",
      "AliyunExpressConnectFullAccess", "AliyunBSSFullAccess"
    ]
  },
  {
    "name"          = "SecurityAdmin"
    "description"   = "Administrator role for billing"
    "system_policy" = [
      "AliyunYundunCloudFirewallFullAccess", "AliyunCSASFullAccess", "AliyunYundunSASFullAccess",
      "AliyunYundunAntiDDoSPremiumFullAccess",
      "AliyunYundunDDoSRewardsFullAccess", "AliyunYundunNewBGPAntiDDoSServicePROFullAccess",
      "AliyunYundunAntiDDoSBagFullAccess",
      "AliyunYundunDDosFullAccess", "AliyunYundunWAFFullAccess", "AliyunBSSFullAccess"
    ]
  },
  {
    "name"          = "DBAdmin"
    "description"   = "Administrator role for database"
    "system_policy" = [
      "AliyunMongoDBFullAccess", "AliyunGPDBFullAccess", "AliyunADBFullAccess", "AliyunGDBFullAccess",
      "AliyunPolardbFullAccess", "AliyunDBSFullAccess", "AliyunHiTSDBFullAccess", "AliyunBSSFullAccess"
    ]
  },
  {
    "name"          = "BigdataAdmin"
    "description"   = "Administrator role for bigdata"
    "system_policy" = [
      "AliyunDataWorksFullAccess", "AliyunDataHubFullAccess", "AliyunDataVFullAccess", "AliyunDLAFullAccess",
      "AliyunEMRFullAccess", "AliyunPAIEASFullAccess", "AliyunElasticsearchFullAccess", "AliyunBSSFullAccess"
    ]
  },
  {
    "name"          = "ReadOnly"
    "description"   = "ReadOnly"
    "system_policy" = ["ReadOnlyAccess"]
  },
  {
    "name"          = "ContainerAdmin"
    "description"   = "Administrator role for container"
    "system_policy" = ["AliyunContainerRegistryFullAccess", "AliyunCSFullAccess", "AliyunBSSFullAccess"]
  },
  {
    "name"          = "ContainerReadOnly"
    "description"   = "Administrator role for container"
    "system_policy" = ["AliyunCSReadOnlyAccess", "AliyunContainerRegistryFullAccess"]
  }
]

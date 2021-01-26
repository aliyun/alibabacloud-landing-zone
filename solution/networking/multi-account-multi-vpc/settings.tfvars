access_key = ""
secret_key = ""
region     = "cn-shanghai"

# 共享账号 cen配置信息
share_service_account_cen = {
  share_service_account_id = "1000308607385840"
  cen = {
    instance_name = "cen"
  }
}

# 业务账号2 VPC 配置信息
member_account_vpc = {
  member_account_id = "1537502672942416"
  vpc = {
    cidr_block = "10.34.64.0/20"
    vpc_name   = "business1"
    projects = {
      #一个项目有多个vswitch, 比如一个vswitch挂ecs,一个vswitch挂rds.  可以按照项目维度如下定义
      "project1" : {
        network_acl_enabled = true
        vswitches = {
          "vsw-app-1-a" : {
            cidr_block = "10.34.64.0/24"
            zone       = "cn-shanghai-f"
            nat = {
              # 是否在 VPC 内创建 NAT Gateway
              natgateway_enabled = true
              # NAT Gateway 名称
              natgateway_name = "nat-business1"
              specification   = "Small"
              nat_type        = "Enhanced"
              # NAT Gateway 绑定的 EIP 带宽
              eip_bandwidth = "10"
              # NAT Gateway 绑定的 EIP 付费方式
              eip_internet_charge_type = "PayByBandwidth"
              eip_tags                 = { team = "teamValue" }
            }
          },
          "vsw-db-1-a" : {
            cidr_block = "10.34.70.0/24"
            zone       = "cn-shanghai-f"
            nat = {
              # 是否在 VPC 内创建 NAT Gateway
              natgateway_enabled = false
            }
          }
        }
      }

      "project2" : {
        network_acl_enabled = true
        vswitches = {
          "vsw-app-2-a" : {
            cidr_block = "10.34.72.0/24"
            zone       = "cn-shanghai-f"
            nat = {
              # 是否在 VPC 内创建 NAT Gateway
              natgateway_enabled = true
              # NAT Gateway 名称
              natgateway_name = "nat-business2"
              specification   = "Small"
              nat_type        = "Enhanced"
              # NAT Gateway 绑定的 EIP 带宽
              eip_bandwidth = "10"
              # NAT Gateway 绑定的 EIP 付费方式
              eip_internet_charge_type = "PayByBandwidth"
              eip_tags                 = { team = "teamValue" }
            }
          },
          "vsw-db-2-a" : {
            cidr_block = "10.34.74.0/24"
            zone       = "cn-shanghai-f"
            nat = {
              # 是否在 VPC 内创建 NAT Gateway
              natgateway_enabled = false
            }
          }
        }
      }
    }
  }
}

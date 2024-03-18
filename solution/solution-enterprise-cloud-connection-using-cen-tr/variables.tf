variable "user1_id" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ResourceManager::Account",
    "Label": {
        "zh-cn": "账号1的ID"
    }
  }
  EOT
}

variable "user2_id" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ResourceManager::Account",
    "Label": {
        "zh-cn": "账号2的ID"
    }
  }
  EOT
}

locals {
  role_name = "ResourceDirectoryAccountAccessRole"
}


variable "create_ecs" {
  type = bool
  description = <<EOT
  {
    "Label": {
        "zh-cn": "是否创建ECS进行网络连通测试"
    }
  }
  EOT
  default = false
}

variable "region1" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ECS::RegionId",
    "Description":"企业版转发路由器支持的地域和可用区: https://help.aliyun.com/document_detail/181681.html",
    "Label": {
        "zh-cn": "资源部署地域1"
    }
  }
  EOT
  default = "cn-beijing"
}

variable "region2" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ECS::RegionId",
    "Label": {
        "zh-cn": "资源部署地域2"
    }
  }
  EOT
  default = "cn-shanghai"
}


variable "region1_zone_id" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ZoneId",
    "AssociationPropertyMetadata": {
        "RegionId": "$${region1}"
    },
    "Label": {
        "zh-cn": "地域1的可用区ID"
    }
  }
  EOT
  default = "cn-beijing-k"
}
variable "region2_zone_id" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ZoneId",
    "AssociationPropertyMetadata": {
        "RegionId": "$${region2}"
    },
    "Label": {
        "zh-cn": "地域2的可用区ID"
    }
  }
  EOT
  default = "cn-shanghai-g"
}

variable "region1_instance_type" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ECS::Instance::InstanceType",
    "AssociationPropertyMetadata": {
      "RegionId": "$${region1}",
      "ZoneId": "$${region1_zone_id}",
      "Visible": {
        "Condition": {
          "Fn::Equals": [
            "$${create_ecs}",
            true
          ]
        }
      }
    },
    "Label": {
        "zh-cn": "地域1的ECS实例规格"
    }
  }
  EOT
  default = "ecs.g6.large"
}

variable "region1_system_disk_category" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ECS::Disk::SystemDiskCategory",
    "AssociationPropertyMetadata": {
      "RegionId": "$${region1}",
      "ZoneId": "$${region1_zone_id}",
      "InstanceType": "$${region1_instance_type}",
      "Visible": {
        "Condition": {
          "Fn::Equals": [
            "$${create_ecs}",
            true
          ]
        }
      }
    },
    "Label": {
        "zh-cn": "地域1的ECS系统盘类型"
    }
  }
  EOT
  default = "cloud_essd"
}

variable "region2_system_disk_category" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ECS::Disk::SystemDiskCategory",
    "AssociationPropertyMetadata": {
      "RegionId": "$${region2}",
      "ZoneId": "$${region2_zone_id}",
      "InstanceType": "$${region2_instance_type}",
      "Visible": {
        "Condition": {
          "Fn::Equals": [
            "$${create_ecs}",
            true
          ]
        }
      }
    },
    "Label": {
        "zh-cn": "地域2的ECS系统盘类型"
    }
  }
  EOT
  default = "cloud_essd"
}

variable "region2_instance_type" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ECS::Instance::InstanceType",
    "AssociationPropertyMetadata": {
      "RegionId": "$${region2}",
      "ZoneId": "$${region2_zone_id}",
      "Visible": {
        "Condition": {
          "Fn::Equals": [
            "$${create_ecs}",
            true
          ]
        }
      }
    },
    "Label": {
        "zh-cn": "地域2的ECS实例规格"
    }
  }
  EOT
  default = "ecs.g5.large"
}

variable "ecs_password" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ECS::Instance::Password",
    "Label": {
        "zh-cn": "ECS实例密码"
    },
    "AssociationPropertyMetadata": {
      "Visible": {
        "Condition": {
          "Fn::Equals": [
            "$${create_ecs}",
            true
          ]
        }
      }
    }
  }
  EOT
  default = "Ros12345"
}

# user1 parameters
variable "user1_region1_vpc_cidr_block" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VPC::CidrBlock",
    "Label": {
        "zh-cn": "账号1在地域1的VPC的CIDR"
    }
  }
  EOT
  default = "172.16.0.0/16"
}

variable "user1_region1_vsw_cidr_block" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VSwitch::CidrBlock",
    "Label": {
        "zh-cn": "账号1在地域1的VSW的CIDR"
    }
  }
  EOT
  default = "172.16.0.0/24"
}

variable "user1_region2_vpc_cidr_block" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VPC::CidrBlock",
    "Label": {
        "zh-cn": "账号1在地域2的VPC的CIDR"
    }
  }
  EOT
  default = "172.17.0.0/16"
}

variable "user1_region2_vsw_cidr_block" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VSwitch::CidrBlock",
    "Label": {
        "zh-cn": "账号1在地域2的VSW的CIDR"
    }
  }
  EOT
  default = "172.17.0.0/24"
}

# user2 parameters
variable "user2_region1_vpc_cidr_block" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VPC::CidrBlock",
    "Label": {
        "zh-cn": "账号2在地域1的VPC的CIDR"
    }
  }
  EOT
  default = "172.18.0.0/16"
}

variable "user2_region1_vsw_cidr_block" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VSwitch::CidrBlock",
    "Label": {
        "zh-cn": "账号2在地域1的VSW的CIDR"
    }
  }
  EOT
  default = "172.18.0.0/24"
}

variable "user2_region2_vpc_cidr_block" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VPC::CidrBlock",
    "Label": {
        "zh-cn": "账号2在地域2的VPC的CIDR"
    }
  }
  EOT
  default = "172.19.0.0/16"
}

variable "user2_region2_vsw_cidr_block" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VSwitch::CidrBlock",
    "Label": {
        "zh-cn": "账号2在地域2的VSW的CIDR"
    }
  }
  EOT
  default = "172.19.0.0/24"
}

variable "user2_connect_vpc_cidr_block" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VPC::CidrBlock",
    "Label": {
        "zh-cn": "账号2互联VPC的CIDR"
    }
  }
  EOT
  default = "172.20.0.0/16"
}

variable "user2_connect_vsw_cidr_block" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VSwitch::CidrBlock",
    "Label": {
        "zh-cn": "账号2互联VSW的CIDR"
    }
  }
  EOT
  default = "172.20.0.0/24"
}

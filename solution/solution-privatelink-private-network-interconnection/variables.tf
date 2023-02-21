
variable "use_resource_directory" {
  type = bool
  description = <<EOT
  {
    "Label": {
        "zh-cn": "是否使用资源目录的账号进行部署"
    }
  }
  EOT
  default = true
}

variable "role_name" {
  description = <<EOT
  {
    "AssociationPropertyMetadata": {
      "Visible": {
        "Condition": {
          "Fn::Equals": [
            "$${use_resource_directory}",
            false
          ]
        }
      }
    },
    "Label": {
        "zh-cn": "扮演账号2的RAM角色名称"
    }
  }
  EOT
  default = "ResourceDirectoryAccountAccessRole"
}

variable "user2_id_not_from_rd" {
  description = <<EOT
  {
    "AssociationPropertyMetadata": {
      "Visible": {
        "Condition": {
          "Fn::Equals": [
            "$${use_resource_directory}",
            false
          ]
        }
      }
    },
    "Label": {
        "zh-cn": "账号2的ID"
    }
  }
  EOT
  default = ""
}

variable "user1_id" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ResourceManager::Account",
    "AssociationPropertyMetadata": {
      "Visible": {
        "Condition": {
          "Fn::Equals": [
            "$${use_resource_directory}",
            true
          ]
        }
      }
    },
    "Label": {
        "zh-cn": "账号1的ID"
    }
  }
  EOT
  default = ""
}

variable "user2_id" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ResourceManager::Account",
    "AssociationPropertyMetadata": {
      "Visible": {
        "Condition": {
          "Fn::Equals": [
            "$${use_resource_directory}",
            true
          ]
        }
      }
    },
    "Label": {
        "zh-cn": "账号2的ID"
    }
  }
  EOT
  default = ""
}

variable "create_ecs" {
  type = bool
  description = <<EOT
  {
    "Label": {
        "zh-cn": "是否创建Ecs进行网络连通测试"
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
  default = "cn-hangzhou"
}


variable "zone1_id" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ZoneId",
    "AssociationPropertyMetadata": {
        "RegionId": "$${region1}"
    },
    "Label": {
        "zh-cn": "账号2部署的交换机1可用区ID"
    }
  }
  EOT
  default = "cn-beijing-g"
}

variable "zone2_id" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ZoneId",
    "AssociationPropertyMetadata": {
        "RegionId": "$${region1}"
    },
    "Label": {
        "zh-cn": "账号1部署的交换机2可用区ID"
    }
  }
  EOT
  default = "cn-beijing-g"
}

variable "zone4_id" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ZoneId",
    "AssociationPropertyMetadata": {
        "RegionId": "$${region2}"
    },
    "Label": {
        "zh-cn": "账号1部署的交换机3可用区ID"
    }
  }
  EOT
  default = "cn-hangzhou-i"
}

variable "instance_type1" {
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ECS::Instance::InstanceType",
    "AssociationPropertyMetadata": {
      "RegionId": "$${region1}",
      "ZoneId": "$${zone1_id}",
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
        "zh-cn": "账号2部署的ECS实例规格"
    }
  }
  EOT
  default = "ecs.g6.large"
}

variable "instance_type2" {
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ECS::Instance::InstanceType",
    "AssociationPropertyMetadata": {
      "RegionId": "$${region1}",
      "ZoneId": "$${zone2_id}",
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
        "zh-cn": "账号1部署的ECS实例规格1"
    }
  }
  EOT
  default = "ecs.g6.large"
}

variable "instance_type4" {
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ECS::Instance::InstanceType",
    "AssociationPropertyMetadata": {
      "RegionId": "$${region2}",
      "ZoneId": "$${zone4_id}",
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
        "zh-cn": "账号1部署的ECS实例规格2"
    }
  }
  EOT
  default = "ecs.g6.large"
}

variable "system_disk_category1" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ECS::Disk::SystemDiskCategory",
    "AssociationPropertyMetadata": {
      "RegionId": "$${region1}",
      "ZoneId": "$${zone1_id}",
      "InstanceType": "$${instance_type1}",
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
        "zh-cn": "账号2部署的ECS系统盘类型"
    }
  }
  EOT
  default = "cloud_essd"
}

variable "system_disk_category2" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ECS::Disk::SystemDiskCategory",
    "AssociationPropertyMetadata": {
      "RegionId": "$${region1}",
      "ZoneId": "$${zone2_id}",
      "InstanceType": "$${instance_type2}",
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
        "zh-cn": "账号1部署的ECS系统盘类型1"
    }
  }
  EOT
  default = "cloud_essd"
}


variable "system_disk_category4" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ECS::Disk::SystemDiskCategory",
    "AssociationPropertyMetadata": {
      "RegionId": "$${region2}",
      "ZoneId": "$${zone4_id}",
      "InstanceType": "$${instance_type4}",
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
        "zh-cn": "账号1部署的ECS系统盘类型2"
    }
  }
  EOT
  default = "cloud_essd"
}


variable "user1_ecs_password" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ECS::Instance::Password",
    "Label": {
        "zh-cn": "账号1部署的ECS实例密码"
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

variable "user2_ecs_password" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ECS::Instance::Password",
    "Label": {
        "zh-cn": "账号2部署的ECS实例密码"
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

variable "vpc1_cidr" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VPC::CidrBlock",
    "Label": {
        "zh-cn": "账号2部署VPC1的CIDR"
    }
  }
  EOT
  default = "10.0.0.0/19"
}

variable "vsw1_cidr" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VSwitch::CidrBlock",
    "Label": {
        "zh-cn": "账号2部署交换机1的CIDR（所属VPC1）"
    }
  }
  EOT
  default = "10.0.0.0/24"
}

variable "vpc2_cidr" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VPC::CidrBlock",
    "Label": {
        "zh-cn": "账号1部署VPC2的CIDR"
    }
  }
  EOT
  default = "10.0.0.0/8"
}

variable "vsw2_cidr" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VSwitch::CidrBlock",
    "Label": {
        "zh-cn": "账号1部署交换机2的CIDR（所属VPC2）"
    }
  }
  EOT
  default = "10.1.0.0/16"
}


variable "vpc3_cidr" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VPC::CidrBlock",
    "Label": {
        "zh-cn": "账号1部署VPC3的CIDR"
    }
  }
  EOT
  default = "172.16.64.0/19"
}

variable "vsw4_cidr" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VSwitch::CidrBlock",
    "Label": {
        "zh-cn": "账号1部署交换机3的CIDR（所属VPC3）"
    }
  }
  EOT
  default = "172.16.64.0/24"
}


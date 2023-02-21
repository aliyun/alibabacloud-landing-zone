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

variable "region" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ECS::RegionId",
    "Description":"企业版转发路由器支持的地域和可用区: https://help.aliyun.com/document_detail/181681.html",
    "Label": {
        "zh-cn": "资源部署地域"
    }
  }
  EOT
  default = "cn-beijing"
}


variable "zone_id" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ZoneId",
    "AssociationPropertyMetadata": {
        "RegionId": "$${region}"
    },
    "Label": {
        "zh-cn": "可用区ID"
    }
  }
  EOT
  default = "cn-beijing-k"
}

variable "instance_type" {
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ECS::Instance::InstanceType",
    "AssociationPropertyMetadata": {
      "RegionId": "$${region}",
      "ZoneId": "$${zone_id}",
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
        "zh-cn": "ECS实例规格"
    }
  }
  EOT
  default = "ecs.g6.large"
}

variable "system_disk_category" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ECS::Disk::SystemDiskCategory",
    "AssociationPropertyMetadata": {
      "RegionId": "$${region}",
      "ZoneId": "$${zone_id}",
      "InstanceType": "$${instance_type}",
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
        "zh-cn": "ECS系统盘类型"
    }
  }
  EOT
  default = "cloud_essd"
}

variable "ecs_password" {
  type = string
  sensitive = true
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

variable "vpc1_cidr" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VPC::CidrBlock",
    "Label": {
        "zh-cn": "VPC1的CIDR"
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
        "zh-cn": "VSW1的CIDR"
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
        "zh-cn": "VPC2的CIDR"
    }
  }
  EOT
  default = "10.0.32.0/19"
}

variable "vsw2_cidr" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VSwitch::CidrBlock",
    "Label": {
        "zh-cn": "VSW2的CIDR"
    }
  }
  EOT
  default = "10.0.32.0/24"
}


variable "vpc3_cidr" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VPC::CidrBlock",
    "Label": {
        "zh-cn": "VPC3的CIDR"
    }
  }
  EOT
  default = "10.0.64.0/19"
}

variable "vsw3_cidr" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VSwitch::CidrBlock",
    "Label": {
        "zh-cn": "VSW3的CIDR"
    }
  }
  EOT
  default = "10.0.64.0/24"
}

variable "vpc4_cidr" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VPC::CidrBlock",
    "Label": {
        "zh-cn": "VPC4的CIDR"
    }
  }
  EOT
  default = "10.0.96.0/19"
}

variable "vsw4_cidr" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VSwitch::CidrBlock",
    "Label": {
        "zh-cn": "VSW4的CIDR"
    }
  }
  EOT
  default = "10.0.96.0/24"
}


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
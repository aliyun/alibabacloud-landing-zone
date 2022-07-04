variable "access_key" {
  type        = string
  description = ""
}

variable "secret_key" {
  type        = string
  description = <<EOT
  {
    "AssociationProperty": "Password",
    "MaxLength": 100,
    "MinLength": 5,
    "Description": {
      "zh-cn": "输入拥有资源目录管理权限RAM用户的SK。"
    },
    "Label": {
        "zh-cn": "sk"
     }
  }
  EOT
}



variable "member_account_list" {
  type        = list(string)
  description = <<EOT
  {
    "Description": {
      "zh-cn": "成员账号列表。"
    },
    "Label": {
        "zh-cn": "成员账号"
     }
  }
  EOT
}


variable "userdefined" {
  type        = list(string)
  description = <<EOT
  {
    "Description": {
      "zh-cn": "用户自定义标识。"
    },
    "Label": {
        "zh-cn": "用户自定义标识."
     }
  }
  EOT
}



variable "log_project_name" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "String",
    "MaxLength": 100,
    "MinLength": 5,
    "Description": {
      "zh-cn": "定义操作系统日志统一投递日志账号中的Project名称。"
    },
    "Label": {
        "zh-cn": "日志Project名称"
     }
  }
  EOT
}

variable "log_project_logstore_name" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "String",
    "MaxLength": 100,
    "MinLength": 5,
    "Description": {
      "zh-cn": "定义操作系统日志统一投递日志账号中的Logstore名称。"
    },
    "Label": {
        "zh-cn": "Logstore名称"
     }
  }
  EOT
}

variable "file_pattern" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "String",
    "MaxLength": 100,
    "MinLength": 1,
    "Description": {
      "zh-cn": "日志文件名称（比如：*.log）。"
    },
    "Label": {
        "zh-cn": "日志文件名称"
     }
  }
  EOT
}

variable "file_path" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "String",
    "MaxLength": 100,
    "MinLength": 1,
    "Description": {
      "zh-cn": "日志文件路径（比如：/var/log）。"
    },
    "Label": {
        "zh-cn": "日志文件路径"
     }
  }
  EOT
}

###############
#ecs相关参数配置#
#ros https://help.aliyun.com/document_detail/315578.html
###############
variable "log_account_id" {
  type        = string
  description = <<EOT
  {
    "Label": {
        "zh-cn": "日志账号UID"
     }
  }
  EOT
}

variable "region" {
  type        = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ECS::RegionId",
    "MaxLength": 100,
    "MinLength": 5,
    "Label": {
        "zh-cn": "地域Region"
     }
  }
  EOT
}

variable "image_id" {
  type        = string
  default     = ""
  description = <<EOT
  {
      "Type": "String",
      "AssociationProperty": "ALIYUN::ECS::Instance::ImageId",
      "AssociationPropertyMetadata": {
        "RegionId": "region"
      },
      "Label": {
        "en": "Image",
        "zh-cn": "镜像"
      }
    }
  EOT
}

variable "zone_id" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::ECS::Instance::ZoneId",
    "AssociationPropertyMetadata": {
        "RegionId": "region"
    },
    "Type": "String",
    "Label": {
      "zh-cn": "交换机可用区",
      "en": "VSwitch Availability Zone"
    }
  }
  EOT
}


variable "vpc" {
  type = string
  description = <<EOT
  {
      "AssociationProperty": "ALIYUN::ECS::VPC::VPCId",
      "AssociationPropertyMetadata": {
        "RegionId": "region"
      },
      "Type": "String",
      "Label": {
        "zh-cn": "现有VPC的实例ID",
        "en": "Existing VPC Instance ID"
      }
  }
  EOT
}

variable "vswitch" {
  type = string
  description = <<EOT
  {
      "AssociationProperty": "ALIYUN::ECS::VSwitch::VSwitchId",
      "Type": "String",
      "Label": {
        "zh-cn": "网络交换机ID",
        "en": "VSwitch ID"
      },
      "AssociationPropertyMetadata": {
        "ZoneId": "zone_id",
        "VpcId": "vpc",
        "RegionId": "region"
      }
  }
  EOT
}

variable "securitygroup" {
  type = string
  description = <<EOT
  {
      "Type": "String",
      "Label": {
        "zh-cn": "业务安全组ID",
        "en": "Business Security Group ID"
      },
      "AssociationProperty": "ALIYUN::ECS::SecurityGroup::SecurityGroupId",
      "AssociationPropertyMetadata": {
        "VpcId": "vpc",
        "RegionId": "region"
      }
  }
  EOT
}

variable "instancetype" {
  type = string
  description = <<EOT
  {
      "AssociationPropertyMetadata": {
        "ZoneId": "zone_id",
        "RegionId": "region"
      },
      "AssociationProperty": "ALIYUN::ECS::Instance::InstanceType",
      "Type": "String",
      "Label": {
        "en": "Instance Type",
        "zh-cn": "实例规格"
      }
  }
  EOT
}





variable "instance_password" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "Password",
    "MaxLength": 100,
    "MinLength": 5,
    "Label": {
        "zh-cn": "主机密码"
     }
  }
  EOT
}



variable "internet_max_bandwidth_out" {
  type        = number
  default     = 5
  description = <<EOT
  {
    "AssociationProperty": "String",
    "Label": {
        "zh-cn": "公网带宽（单位Mb）"
     }
  }
  EOT
}

variable "instance_charge_type" {
  type        = string
  description = <<EOT
  {
    "AssociationProperty": "String",
    "AllowedValues": [
        "PrePaid",
        "PostPaid"
      ],
    "Label": {
        "zh-cn": "实例计费类型"
     }
  }
  EOT
  default = "PrePaid"
}

variable "period" {
  type        = number
  description = <<EOT
  {
    "AssociationProperty": "String",
    "Label": {
        "zh-cn": "包年包月周期"
     }
  }
  EOT
  default     = 1
}

variable "period_unit" {
  type        = string
  description = <<EOT
  {
    "AssociationProperty": "String",
    "Label": {
        "zh-cn": "包年包月周期(单位)"
     }
  }
  EOT
  default = "Week"
}



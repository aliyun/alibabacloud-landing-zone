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


variable "user1_vpc_cidr" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VPC::CidrBlock",
    "Label": {
        "zh-cn": "VPC1的CIDR"
    }
  }
  EOT
  default = "10.0.0.0/16"
}

variable "user1_pod_vsw_cidr" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VSwitch::CidrBlock",
    "Label": {
        "zh-cn": "账号1Pod节点VSW的CIDR"
    }
  }
  EOT
  default = "10.0.128.0/20"
}

variable "user1_node_vsw_cidr" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VSwitch::CidrBlock",
    "Label": {
        "zh-cn": "账号1Node节点VSW的CIDR"
    }
  }
  EOT
  default = "10.0.0.0/24"
}

variable "user1_service_cidr" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VPC::CidrBlock",
    "Label": {
        "zh-cn": "账号1Service节点的CIDR"
    }
  }
  EOT
  default = "172.16.0.0/20"
}

variable "user2_vpc_cidr" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VPC::CidrBlock",
    "Label": {
        "zh-cn": "VPC2的CIDR"
    }
  }
  EOT
  default = "192.168.0.0/16"
}

variable "user2_pod_vsw_cidr" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VSwitch::CidrBlock",
    "Label": {
        "zh-cn": "账号2Pod节点VSW的CIDR"
    }
  }
  EOT
  default = "192.168.16.0/20"
}


variable "user2_node_vsw_cidr" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VSwitch::CidrBlock",
    "Label": {
        "zh-cn": "账号2Node节点VSW的CIDR"
    }
  }
  EOT
  default = "192.168.0.0/24"
}

variable "user2_service_cidr" {
  type = string
  description = <<EOT
  {
    "AssociationProperty": "ALIYUN::VPC::VPC::CidrBlock",
    "Label": {
        "zh-cn": "账号2Service节点的CIDR"
    }
  }
  EOT
  default = "172.16.16.0/20"
}

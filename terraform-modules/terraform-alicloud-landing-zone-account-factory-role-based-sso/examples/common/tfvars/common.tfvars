metadata_file_path = "./metadata.xml"
ram_roles = [
  {
    name = "Admin"
    description = "Super admin"
    policies = [
      "AdministratorAccess"
    ]
  },
  {
    name = "LogAdmin"
    description = "Log service full access"
    policies = [
      "AliyunLogFullAccess"
    ]
  },
  {
    name = "NetworkAdmin"
    description = "VPC/SLB/CEN... full access"
    policies = [
      "AliyunVPCFullAccess",
      "AliyunNATGatewayFullAccess",
      "AliyunEIPFullAccess",
      "AliyunCENFullAccess",
      "AliyunSLBFullAccess"
    ]
  }
]
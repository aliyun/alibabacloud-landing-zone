provider "alicloud" {
  alias = "sharedservices"
}

######################## [企业管理账号身份集成]##################

# 获取当前主账号的信息
data "alicloud_account" "current_account" {
}

# 在主账号下创建role: AzureAD-CloudAdmin
# resource "alicloud_ram_role" "ram_role_AzureAD-CloudAdmin" {
#   name        = "AzureAD-CloudAdmin"
#   document    = <<EOF
#   {
#     "Statement": [
#         {
#             "Action": "sts:AssumeRole",
#             "Condition": {
#                 "StringEquals": {
#                     "saml:recipient": "https://signin.aliyun.com/saml-role/sso"
#                 }
#             },
#             "Effect": "Allow",
#             "Principal": {
#                 "Federated": [
#                     "acs:ram::${data.alicloud_account.current_account.id}:saml-provider/Azure"
#                 ]
#             }
#         }
#     ],
#     "Version": "1"
#   }
#   EOF
#   description = "AzureAD-CloudAdmin"
#   force       = true
# }

# # 在主账号下创建role: AzureAD-BillingAdmin
# resource "alicloud_ram_role" "ram_role_AzureAD-BillingAdmin" {
#   name        = "AzureAD-BillingAdmin"
#   document    = <<EOF
#   {
#     "Statement": [
#         {
#             "Action": "sts:AssumeRole",
#             "Condition": {
#                 "StringEquals": {
#                     "saml:recipient": "https://signin.aliyun.com/saml-role/sso"
#                 }
#             },
#             "Effect": "Allow",
#             "Principal": {
#                 "Federated": [
#                     "acs:ram::${data.alicloud_account.current_account.id}:saml-provider/Azure"
#                 ]
#             }
#         }
#     ],
#     "Version": "1"
#   }
#   EOF
#   description = "AzureAD-BillingAdmin"
#   force       = true
# }

# # 为角色AzureAD-CloudAdmin 授权: AdministratorAccess
# resource "alicloud_ram_role_policy_attachment" "AzureAD-CloudAdmin_AdministratorAccess" {
#   policy_name = "AdministratorAccess"
#   policy_type = "System"
#   role_name   = alicloud_ram_role.ram_role_AzureAD-CloudAdmin.name
# }

# # 为角色AzureAD-BillingAdmin 授权: AliyunBSSFullAccess
# resource "alicloud_ram_role_policy_attachment" "AzureAD-BillingAdmin_AliyunBSSFullAccess" {
#   policy_name = "AliyunBSSFullAccess"
#   policy_type = "System"
#   role_name   = alicloud_ram_role.ram_role_AzureAD-BillingAdmin.name
# }

# # 为角色AzureAD-BillingAdmin 授权: AliyunFinanceConsoleFullAccess
# resource "alicloud_ram_role_policy_attachment" "AzureAD-BillingAdmin_AliyunFinanceConsoleFullAccess" {
#   policy_name = "AliyunFinanceConsoleFullAccess"
#   policy_type = "System"
#   role_name   = alicloud_ram_role.ram_role_AzureAD-BillingAdmin.name
# }

# ######################## 企业 SharedServices Account 身份集成 ##################

# # 在资源账号SharedServices下创建role: AzureAD-CloudAdmin
# resource "alicloud_ram_role" "sharedservices_ram_role_AzureAD-CloudAdmin" {
#   provider    = alicloud.sharedservices
#   name        = "AzureAD-CloudAdmin"
#   document    = <<EOF
#   {
#     "Statement": [
#         {
#             "Action": "sts:AssumeRole",
#             "Condition": {
#                 "StringEquals": {
#                     "saml:recipient": "https://signin.aliyun.com/saml-role/sso"
#                 }
#             },
#             "Effect": "Allow",
#             "Principal": {
#                 "Federated": [
#                     "acs:ram::${var.shared_services_account_id}:saml-provider/Azure"
#                 ]
#             }
#         }
#     ],
#     "Version": "1"
#   }
#   EOF
#   description = "AzureAD-CloudAdmin"
#   force       = true
# }

# # 在资源账号SharedServices中的角色AzureAD-CloudAdmin 授权: AdministratorAccess
# resource "alicloud_ram_role_policy_attachment" "sharedservices_AzureAD-CloudAdmin_AdministratorAccess" {
#   provider    = alicloud.sharedservices
#   policy_name = "AdministratorAccess"
#   policy_type = "System"
#   role_name   = alicloud_ram_role.sharedservices_ram_role_AzureAD-CloudAdmin.name
# }



######################################防止文件过长,先略过############################################
# 角色名：AzureAD-NetworkAdmin
# AliyunVPCFullAccess
# AliyunNATGatewayFullAccess
# AliyunEIPFullAccess
# AliyunCENFullAccess
# AliyunVPNGatewayFullAccess
# AliyunSLBFullAccess
# AliyunExpressConnectFullAccess
# AliyunCommonBandwidthPackageFullAccess
# AliyunSmartAccessGatewayFullAccess
# AliyunGlobalAccelerationFullAccess
# AliyunECSNetworkInterfaceManagementAccess
# AliyunDNSFullAccess
# AliyunCDNFullAccess
# AliyunYundunNewBGPAntiDDoSServicePROFullAccess
#------------------------------------------------------------------------------------------------
# 角色名：AzureAD-DBAdmin
# AliyunRDSFullAccess
# AliyunDRDSFullAccess
# AliyunKvstoreFullAccess
# AliyunOCSFullAccess
# AliyunPolardbFullAccess
# AliyunADBFullAccess
# AliyunDTSFullAccess
# AliyunMongoDBFullAccess
# AliyunPetaDataFullAccess
# AliyunGPDBFullAccess
# AliyunHBaseFullAccess
# AliyunYundunDbAuditFullAccess
# AliyunHiTSDBFullAccess
# AliyunDBSFullAccess
# AliyunHDMFullAccess
# AliyunGDBFullAccess
# AliyunADAMFullAccess
# AliyunDBESFullAccess
# AliyunDGFullAccess
# AliyunOceanBaseFullAccess
# AliyunCassandraFullAccess
# AliyunClickHouseFullAccess
# AliyunDLAFullAccess
#------------------------------------------------------------------------------------------------
# 角色名：AzureAD-MonitorAdmin
# AliyunCloudMonitorFullAccess
#------------------------------------------------------------------------------------------------
# 角色名：AzureAD-SecurityAdmin
# AliyunYundunFullAccess
#------------------------------------------------------------------------------------------------
# 角色名：AzureAD-SecurityAuditor
# AliyunYundunHighReadOnlyAccess
# AliyunYundunAegisReadOnlyAccess
# AliyunYundunSASReadOnlyAccess
# AliyunYundunBastionHostReadOnlyAccess
# AliyunYundunCertReadOnlyAccess
# AliyunYundunDDosReadOnlyAccess
# AliyunYundunWAFReadOnlyAccess
# AliyunYundunDbAuditReadOnlyAccess
# AliyunYundunCloudFirewallReadOnlyAccess
# AliyunYundunIdaasReadOnlyAccess
#------------------------------------------------------------------------------------------------
# 角色名：AzureAD-LogAdmin
# AliyunLogFullAccess
#------------------------------------------------------------------------------------------------
# 角色名：AzureAD-LogViewer
# AliyunLogReadOnlyAccess
#------------------------------------------------------------------------------------------------
# 角色名：AzureAD-CommonUser
# 权限：无（客户可自定义）
#------------------------------------------------------------------------------------------------

######################################防止文件过长,先略过############################################
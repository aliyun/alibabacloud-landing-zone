# 配置 AK、Region 等信息
provider "alicloud" {
  access_key = var.access_key
  secret_key = var.secret_key
  region     = var.region
}

# assumerole到成员账号
provider "alicloud" {
  alias      = "memberprovider"
  access_key = var.access_key
  secret_key = var.secret_key
  region     = var.region
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", var.member_account_vpc.member_account_id)
    session_name       = format("%sLandingZoneSetup", var.member_account_vpc.member_account_id)
    session_expiration = 999
  }
}

# assumerole到运维账号
provider "alicloud" {
  alias      = "sharedserviceprovider"
  access_key = var.access_key
  secret_key = var.secret_key
  region     = var.region
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", var.share_service_account_cen.share_service_account_id)
    session_name       = format("%sLandingZoneSetup", var.share_service_account_cen.share_service_account_id)
    session_expiration = 999
  }
}

# 创建 CEN
resource "alicloud_cen_instance" "cen" {
  provider          = alicloud.sharedserviceprovider
  cen_instance_name = var.share_service_account_cen.cen.instance_name
}

# 创建 业务账号下的网络资源 并加载到cen
module "network" {
  providers = {
    alicloud                = alicloud.sharedserviceprovider
    alicloud.memberprovider = alicloud.memberprovider
  }
  source                   = "./network"
  member_account_id        = var.member_account_vpc.member_account_id
  share_service_account_id = var.share_service_account_cen.share_service_account_id
  vpc                      = var.member_account_vpc.vpc
  cen_id                   = alicloud_cen_instance.cen.id
  region                   = var.region
}


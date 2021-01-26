provider "alicloud" {
}

provider "alicloud" {
  alias = "memberprovider"
}

# 创建企业专有网络VPC
resource "alicloud_vpc" "vpc_business" {
  provider   = alicloud.memberprovider
  name       = var.vpc.vpc_name
  cidr_block = var.vpc.cidr_block
}

# 创建业务项目网络
module "network_project" {
  providers = {
    alicloud = alicloud.memberprovider
  }
  source               = "./project"
  for_each             = var.vpc.projects
  project_name         = each.key
  vpc_id               = alicloud_vpc.vpc_business.id
  vswitches            = each.value.vswitches
  network_acl_enabled  = each.value.network_acl_enabled
  cen_id               = var.cen_id
}

# 跨账号加载vpc到cen
resource "alicloud_cen_instance_grant" "cen_instance_grant" {
  provider          = alicloud.memberprovider
  cen_id            = var.cen_id
  child_instance_id = alicloud_vpc.vpc_business.id
  cen_owner_id      = var.share_service_account_id
}

resource "alicloud_cen_instance_attachment" "cen_shared_service_vpc_attachment" {
  instance_id              = var.cen_id
  child_instance_id        = alicloud_vpc.vpc_business.id
  child_instance_region_id = var.region
  child_instance_type      = "VPC"
  child_instance_owner_id  = var.member_account_id
  depends_on               = [alicloud_cen_instance_grant.cen_instance_grant]
}

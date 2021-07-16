provider "alicloud" {
  access_key = var.master_account_access_key
  secret_key = var.master_account_secret_key
  region = var.region
  alias = "master_account"
}

provider "alicloud" {
  access_key = var.master_account_access_key
  secret_key = var.master_account_secret_key
  region = var.region
  alias = "cmdb_account"
  assume_role {
    role_arn = "acs:ram::${var.cmdb_account_uid}:role/ResourceDirectoryAccountAccessRole"
    session_name = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

# 在cmdb账号下创建用于存储配置数据的project和logstore
resource "alicloud_log_project" "example" {
  provider = alicloud.cmdb_account
  name = var.project_name
  description = "create by terraform"
}

resource "alicloud_log_store" "example" {
  provider = alicloud.cmdb_account
  project = alicloud_log_project.example.name
  name = var.logstore_name
}

# 查询企业管理账号下的所有成员账号
data "alicloud_resource_manager_accounts" "rd" {
  provider = alicloud.master_account
}
data "alicloud_account" "master" {
  provider = alicloud.master_account
}

# 将企业管理账号和旗下的所有成员设为全局账号组，当RD中新增/删除成员账号时，账号组会自动扩容/收缩
# 注意: 企业管理账号中只能存在一个全局账号组
resource "alicloud_config_aggregator" "example" {
  provider = alicloud.master_account
  aggregator_type = "RD"

  aggregator_accounts {
    account_id = data.alicloud_account.master.id
    account_name = "master"
    account_type = "ResourceDirectory"
  }

  dynamic "aggregator_accounts" {
    for_each = data.alicloud_resource_manager_accounts.rd.accounts
    content {
      account_id = aggregator_accounts.value.account_id
      account_name = aggregator_accounts.value.display_name
      account_type = "ResourceDirectory"
    }
  }

  aggregator_name = "tf-test"
  description = "create by terraform"
}

# alicloud_config_delivery_channel只能将region设为cn-shanghai/ap-northeast-1
provider "alicloud" {
  access_key = var.master_account_access_key
  secret_key = var.master_account_secret_key
  region = "cn-shanghai"
  alias = "master_account_shanghai"
}

# 设置数据投递，使用cmdb下的aliyunserviceroleforconfig角色，将数据投递到之前创建的project和logstore中
resource "alicloud_config_delivery_channel" "example" {
  provider = alicloud.master_account_shanghai
  # 开启数据投递，注意：使用terraform destroy时不会自动关闭数据投递，需要手动前往控制台关闭
  status = "1"
  description = "create by terraform"
  delivery_channel_name = "tf--delivery-channel"
  delivery_channel_assume_role_arn = "acs:ram::${var.cmdb_account_uid}:role/aliyunserviceroleforconfig"
  delivery_channel_type = "SLS"
  delivery_channel_target_arn = "acs:log:${var.region}:${var.cmdb_account_uid}:project/${alicloud_log_project.example.name}/logstore/${alicloud_log_store.example.name}"
}
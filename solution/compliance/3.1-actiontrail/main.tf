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
  alias = "actiontrail_account"
  assume_role {
    role_arn = "acs:ram::${var.actiontrail_account_uid}:role/ResourceDirectoryAccountAccessRole"
    session_name = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}

# step1: 在日志审计账号中创建用于接收操作事件的SLS Logstore和OSS Bucket
# 创建Project。Logstore不需要手动创建，在创建跟踪后操作审计会生成默认的Logstore
resource "alicloud_log_project" "example" {
  provider = alicloud.actiontrail_account
  name = var.project_name
  description = "create by terraform"
}

# 创建bucket
resource "alicloud_oss_bucket" "example" {
  provider = alicloud.actiontrail_account
  bucket = var.bucket_name
  acl = "private"

  lifecycle_rule {
    enabled = "true"

    # 过期时间
    expiration {
      days = 730
    }
  }

  # 服务端加密
  server_side_encryption_rule {
    sse_algorithm = "AES256"
  }

  # 允许强制删除，根据业务需求是否开启。开启后当Bucket中有数据时也能删除；不开启时Terraform destroy无法直接删除Bucket，需要手动将bucket内的文件全部删除后才可删除。
  force_destroy = "true"
}

# step2: 在日志审计账号中创建RAM角色，并授权操作审计向审计账号投递操作事件的权限
data "alicloud_account" "master" {
  provider = alicloud.master_account
}

resource "alicloud_ram_role" "role" {
  provider = alicloud.actiontrail_account
  name = "ActionTrailDeliveryRole"
  document = <<EOF
  {
    "Statement": [
      {
        "Action": "sts:AssumeRole",
        "Effect": "Allow",
        "Principal": {
          "Service": [
            "${data.alicloud_account.master.id}@actiontrail.aliyuncs.com"
          ]
        }
      }
    ],
    "Version": "1"
  }
  EOF
  description = "create by terraform, use for actionTrail delivery"
}

resource "alicloud_ram_role_policy_attachment" "attach" {
  provider = alicloud.actiontrail_account
  policy_name = "AliyunActionTrailDeliveryPolicy"
  policy_type = "System"
  role_name = alicloud_ram_role.role.name
}

# step3: 创建多账号跟踪将所有成员账号的操作事件投递到创建好的存储空间
resource "alicloud_actiontrail_trail" "example" {
  provider = alicloud.master_account
  trail_name = "action-trail"
  event_rw = "All"

  # 将事件投递到日志审计账号下的日志服务Logstore中
  sls_project_arn = "acs:log:${var.region}:${var.actiontrail_account_uid}:project/${alicloud_log_project.example.name}"
  sls_write_role_arn = alicloud_ram_role.role.arn

  # 将事件投递到其他账号下的对象存储Bucket中
  oss_bucket_name = alicloud_oss_bucket.example.id
  oss_write_role_arn = alicloud_ram_role.role.arn
  is_organization_trail = "true"
}
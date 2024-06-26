provider "alicloud" {
  region = var.logarchive_central_region
}

# assume role to account
provider "alicloud" {
  alias  = "delivery"
  region = var.delivery_region
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", var.delivery_account_id)
    session_name       = "WellArchitectedSolutionSetup"
    session_expiration = 999
  }
}

provider "alicloud" {
  alias  = "logarchive"
  region = var.logarchive_central_region
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", var.logarchive_account_id)
    session_name       = "WellArchitectedSolutionSetup"
    session_expiration = 999
  }
}

# create ram role for delivery account
module "delivery_ram_role" {
  source = "../modules/ram-role"
  providers = {
    alicloud = alicloud.delivery
  }
  role_name       = format("%slogaudit-data-transformation-role", var.delivery_ram_name_prefix)
  role_document   = <<EOF
  {
    "Statement": [
      {
        "Action": "sts:AssumeRole",
        "Effect": "Allow",
        "Principal": {
          "Service": [
            "log.aliyuncs.com"
          ]
        }
      }
    ],
    "Version": "1"
  }
  EOF
  policy_name     = format("%slogaudit-data-transformation-policy", var.delivery_ram_name_prefix)
  policy_document = <<EOF
  {
    "Version": "1",
    "Statement": [
        {
            "Action": [
                "log:ListShards",
                "log:GetCursorOrData",
                "log:GetConsumerGroupCheckPoint",
                "log:UpdateConsumerGroup",
                "log:ConsumerGroupHeartBeat",
                "log:ConsumerGroupUpdateCheckPoint",
                "log:ListConsumerGroup",
                "log:CreateConsumerGroup"
            ],
            "Resource": [
                "acs:log:*:*:project/${var.delivery_sls_project}/logstore/${var.delivery_sls_logstore}",
                "acs:log:*:*:project/${var.delivery_sls_project}/logstore/${var.delivery_sls_logstore}/*"
            ],
            "Effect": "Allow"
        }
    ]
  }
  EOF
}

module "logarchive_ram_role" {
  source = "../modules/ram-role"
  count  = try(var.is_logarchive_ram_role_existed, false) == true ? 0 : 1
  providers = {
    alicloud = alicloud.logarchive
  }
  role_name       = var.logarchive_ram_role_name
  role_document   = <<EOF
  {
    "Statement": [
      {
        "Action": "sts:AssumeRole",
        "Effect": "Allow",
        "Principal": {
          "Service": [
            "${var.delivery_account_id}@log.aliyuncs.com"
          ]
        }
      }
    ],
    "Version": "1"
  }
  EOF
  policy_name     = format("%s-policy", var.logarchive_ram_role_name)
  policy_document = <<EOF
  {
    "Version": "1",
    "Statement": [
        {
            "Action": [
                "log:Post*",
                "log:BatchPost*"
            ],
            "Resource": "acs:log:*:*:project/${var.central_sls_project}/logstore/${var.central_sls_logstore_name}",
            "Effect": "Allow"
        }
    ]
  }
  EOF
}

resource "alicloud_log_store" "logarchive" {
  count                 = try(var.is_central_sls_logstore_existed, false) == true ? 0 : 1
  provider              = alicloud.logarchive
  auto_split            = true
  max_split_shard_count = 64
  name                  = var.central_sls_logstore_name
  project               = var.central_sls_project
  retention_period      = 180
  shard_count           = 2
  hot_ttl               = 30
}

# create data transformation
resource "alicloud_log_etl" "delivery" {
  provider     = alicloud.delivery
  etl_name     = "delivery-to-central-logarchive"
  project      = var.delivery_sls_project
  display_name = "delivery-to-central-logarchive"
  logstore     = var.delivery_sls_logstore
  role_arn     = format("acs:ram::%s:role/%s", var.delivery_account_id, format("%slogaudit-data-transformation-role", var.delivery_ram_name_prefix))
  script       = ""
  etl_sinks {
    name     = "central-logarchive"
    project  = var.central_sls_project
    logstore = var.central_sls_logstore_name
    role_arn = format("acs:ram::%s:role/%s", var.logarchive_account_id, var.logarchive_ram_role_name)
    endpoint = format("https://%s.log.aliyuncs.com", var.logarchive_central_region)
  }

  depends_on = [
    module.delivery_ram_role,
    module.logarchive_ram_role,
    alicloud_log_store.logarchive
  ]
}

provider "alicloud" {
  region = var.logarchive_central_region
}

# assume role to logarchive account
provider "alicloud" {
  alias  = "logarchive"
  region = var.logarchive_central_region
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", var.logarchive_account_id)
    session_name       = "WellArchitectedSolutionSetup"
    session_expiration = 999
  }
}

provider "random" {}

resource "alicloud_oss_bucket" "logarchive" {
  count           = try(var.is_oss_bucket_existed, false) == true ? 0 : 1
  provider        = alicloud.logarchive
  bucket          = var.oss_bucket_name
  storage_class   = "ColdArchive"
  redundancy_type = "LRS"
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
            "log.aliyuncs.com"
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
            "Effect": "Allow",
            "Action": [
                "log:GetCursorOrData",
                "log:ListShards"
            ],
            "Resource": [
                "acs:log:*:*:project/${var.central_sls_project}/logstore/*",
                "acs:log:*:*:project/${var.central_sls_project}/logstore/*/*"
            ]
        },
        {
            "Effect": "Allow",
            "Action": "oss:PutObject",
            "Resource": [
                "acs:oss:*:*:${var.oss_bucket_name}",
                "acs:oss:*:*:${var.oss_bucket_name}/*"
            ]
        }
    ]
  }
  EOF
}

# log cold archive to oss
resource "random_string" "logarchive" {
  for_each = {
    for export in var.central_sls_logstore_exports : export.logstore_name => export
  }

  length  = 16
  special = false
  upper   = false
}

resource "alicloud_log_oss_export" "logarchive" {
  for_each = {
    for export in var.central_sls_logstore_exports : export.logstore_name => export
  }

  provider          = alicloud.logarchive
  project_name      = var.central_sls_project
  logstore_name     = each.key
  export_name       = format("%s%s", each.value.export_name_prefix == null ? "cold-logarchive-" : each.value.export_name_prefix, random_string.logarchive[each.key].result)
  display_name      = "cold-logarchive"
  prefix            = try(each.value.oss_bucket_directory, null)
  suffix            = try(each.value.suffix, null)
  bucket            = var.oss_bucket_name
  buffer_interval   = try(each.value.buffer_interval, null) == null ? 300 : each.value.buffer_interval
  buffer_size       = try(each.value.buffer_size, null) == null ? 256 : each.value.buffer_size
  role_arn          = format("acs:ram::%s:role/%s", var.logarchive_account_id, var.logarchive_ram_role_name)
  log_read_role_arn = format("acs:ram::%s:role/%s", var.logarchive_account_id, var.logarchive_ram_role_name)
  compress_type     = try(each.value.compress_type, null) == null ? "snappy" : each.value.compress_type
  path_format       = "%Y/%m/%d/%H/%M"
  time_zone         = try(each.value.time_zone, null) == null ? "+0800" : each.value.time_zone
  content_type      = try(each.value.content_type, null) == null ? "json" : each.value.content_type

  depends_on = [
    alicloud_oss_bucket.logarchive,
    module.logarchive_ram_role
  ]
}

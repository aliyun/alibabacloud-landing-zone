# Retrieve current account information
data "alicloud_account" "master" {
  provider = alicloud.master_account
}
data "alicloud_account" "logarchive" {
  provider = alicloud.log_archive_account
}

# Activate oss and sls service in log archive account
data "alicloud_oss_service" "enable_oss_service" {
  provider = alicloud.log_archive_account
  enable = "On"
}

data "alicloud_log_service" "enable_sls_service" {
  provider = alicloud.log_archive_account
  enable = "On"
}

# Create log store or oss bucket in log archive account
resource "alicloud_log_project" "actiontrail" {
  count = var.sls_project_name_for_actiontrail != "" ? 1 : 0
  
  name = var.sls_project_name_for_actiontrail

  tags = {"landingzone": "logarchive"}
}

resource "alicloud_oss_bucket" "actiontrail" {
  count = var.oss_bucket_name_for_actiontrail != "" ? 1 : 0

  bucket = var.oss_bucket_name_for_actiontrail
  acl = "private"

  server_side_encryption_rule {
    sse_algorithm = "AES256"
  }

  tags = {"landingzone": "logarchive"}
}

locals {
  actiontrail_sls_project_arn = var.sls_project_name_for_actiontrail != "" ? format("acs:log:%s:%s:project/%s", var.sls_project_region_for_actiontrail, data.alicloud_account.logarchive.id, alicloud_log_project.actiontrail.name) : ""
  actiontrail_log_archive_role_document = <<EOF
  {
    "Statement": [
      {
        "Action": "sts:AssumeRole",
        "Effect": "Allow",
        "Principal": {
          "Service": [
            "%s@actiontrail.aliyuncs.com"
          ]
        }
      }
    ],
    "Version": "1"
  }
  EOF
}

# Create ActionTrail trail
resource "alicloud_ram_role" "actiontrail" {
  count = local.actiontrail_log_archive_enabled ? 1 : 0

  name        = "ActionTrailLogArchiveRole"
  document    = format(local.actiontrail_log_archive_role_document, data.alicloud_account.master.id)
  description = "This role is used for action trail log archive"
}

resource "alicloud_actiontrail_trail" "trail" {
  count = local.actiontrail_log_archive_enabled ? 1 : 0

  trail_name = var.actiontrail_trail_name
  event_rw = "All"
  oss_bucket_name = var.oss_bucket_name_for_actiontrail != "" ? alicloud_oss_bucket.actiontrail.id : ""
  sls_project_arn = local.actiontrail_sls_project_arn
  oss_write_role_arn = alicloud_ram_role.actiontrail[0].arn
  sls_write_role_arn = alicloud_ram_role.actiontrail[0].arn
  is_organization_trail = true

  provider = alicloud.master_account

  lifecycle {
    ignore_changes = [
      trail_name
    ]
  }
}

provider "alicloud" {

}

# add control policy
resource "alicloud_resource_manager_control_policy" "logarchive" {
  control_policy_name = "ProhibitDeleteLogAudit"
  description         = "Prohibit to delete resources for log archiving and auditing"
  effect_scope        = "RAM"
  policy_document     = <<EOF
  {
    "Version": "1",
    "Statement": [
      {
        "Effect": "Deny",
        "Action": [
          "log:DeleteProject",
          "log:DeleteLogStore"
        ],
        "Resource": [
          "acs:log:*:${var.logarchive_account_id}:project/${var.central_sls_project}",
          "acs:log:*:${var.logarchive_account_id}:project/${var.central_sls_project}/*",
          "acs:log:*:${var.logarchive_account_id}:project/${var.central_sls_project}/logstore/*"
        ]
      },
      {
        "Effect": "Deny",
        "Action": "log:DeleteJob",
        "Resource": [
          %{for i, job_id in var.oss_export_job_ids}
          "acs:log:*:${var.logarchive_account_id}:project/${var.central_sls_project}/job/${job_id}"%{if i != length(var.oss_export_job_ids) - 1},
          %{endif}
          %{endfor} 
        ]
      },
      {
        "Effect": "Deny",
        "Action": "oss:DeleteBucket",
        "Resource": "acs:oss:oss-*:${var.logarchive_account_id}:${var.oss_bucket_name}"
      }
    ]
  }
  EOF
}

resource "alicloud_resource_manager_control_policy_attachment" "logarchive" {
  policy_id = alicloud_resource_manager_control_policy.logarchive.id
  target_id = var.logarchive_account_id
}

output "oss_export_job_ids" {
  value = [
    for k, v in alicloud_log_oss_export.logarchive : v.export_name
  ]
}

output "oss_export_jobs" {
  value = [
    for k, v in alicloud_log_oss_export.logarchive : {
      id            = v.id
      job_id        = v.export_name
      project_name  = v.project_name
      logstore_name = v.logstore_name
      bucket_name   = v.bucket
    }
  ]
}

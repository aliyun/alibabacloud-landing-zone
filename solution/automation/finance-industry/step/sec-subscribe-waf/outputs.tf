output "waf_id" {
  value = alicloud_waf_instance.waf_instance.id
}

output "shared_service_account_waf_cname" {
  value = alicloud_waf_domain.domain_shared_service_account.cname
}

output "dev_account_waf_cname" {
  value = alicloud_waf_domain.domain_dev_account.cname
}
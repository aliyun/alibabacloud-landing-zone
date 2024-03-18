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

# create delegated administrator
resource "alicloud_resource_manager_delegated_administrator" "logarchive" {
  account_id        = var.logarchive_account_id
  service_principal = "audit.log.aliyuncs.com"
}

resource "alicloud_resource_manager_service_linked_role" "logarchive" {
  provider     = alicloud.logarchive
  service_name = "audit.log.aliyuncs.com"
}

# create sls log audit application
resource "alicloud_log_audit" "logarchive" {
  provider = alicloud.logarchive

  aliuid                  = var.logarchive_account_id
  display_name            = "sls-log-audit-application"
  resource_directory_type = "all"

  variable_map = var.audit_logs

  depends_on = [
    alicloud_resource_manager_delegated_administrator.logarchive,
    alicloud_resource_manager_service_linked_role.logarchive
  ]
}

provider "alicloud" {
  region = var.region
}

resource "alicloud_kms_policy" "kms_instance" {
  kms_instance_id = var.kms_instance_id
  policy_name     = var.kms_instance_policy.name
  description     = var.kms_instance_policy.description
  permissions = [
    "RbacPermission/Template/CryptoServiceKeyUser",
    "RbacPermission/Template/CryptoServiceSecretUser"
  ]
  resources = [
    format("secret/%s", var.kms_managed_ram_secret_name),
    format("key/%s", var.kms_key_id)
  ]
  access_control_rules = <<EOF
  {
    "NetworkRules": ${var.kms_instance_policy.network_rules == null ? "[]" : jsonencode(var.kms_instance_policy.network_rules)}
  }
  EOF
}

resource "alicloud_kms_policy" "shared_gateway" {
  count           = var.kms_shared_gateway_policy.name == null ? 0 : 1
  kms_instance_id = "shared"
  policy_name     = var.kms_shared_gateway_policy.name
  description     = var.kms_shared_gateway_policy.description
  permissions = [
    "RbacPermission/Template/SecretUser"
  ]
  resources = [
    format("secret/%s", var.kms_managed_ram_secret_name)
  ]
  access_control_rules = <<EOF
  {
    "NetworkRules": ${var.kms_shared_gateway_policy.network_rules == null ? "[]" : jsonencode(var.kms_shared_gateway_policy.network_rules)}
  }
  EOF
}

resource "alicloud_kms_application_access_point" "managed_accesskey" {
  application_access_point_name = var.aap_name
  description                   = var.aap_description
  policies = var.kms_shared_gateway_policy.name == null ? [
    var.kms_instance_policy.name
    ] : [
    var.kms_shared_gateway_policy.name,
    var.kms_instance_policy.name
  ]

  depends_on = [
    alicloud_kms_policy.kms_instance,
    alicloud_kms_policy.shared_gateway
  ]
}

resource "alicloud_kms_client_key" "managed_accesskey" {
  aap_name              = var.aap_name
  password              = var.client_key_password
  not_before            = var.client_key_not_before
  not_after             = var.client_key_not_after
  private_key_data_file = var.private_key_data_file == "" ? format("clientKey_KAAP.%s.json", var.kms_instance_id) : var.private_key_data_file

  depends_on = [
    alicloud_kms_application_access_point.managed_accesskey
  ]
}

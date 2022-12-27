locals {
  account_json              = fileexists("../var/account.json") ? jsondecode(file("../var/account.json")) : {}
  log_account_id            = var.log_account_id == "" ? local.account_json["log_account_id"] : var.log_account_id
  shared_service_account_id = var.shared_service_account_id == "" ? local.account_json["shared_service_account_id"] : var.shared_service_account_id
  security_account_id       = var.security_account_id == "" ? local.account_json["security_account_id"] : var.security_account_id
  ops_account_id            = var.ops_account_id == "" ? local.account_json["ops_account_id"] : var.ops_account_id
  dev_account_id            = var.dev_account_id == "" ? local.account_json["dev_account_id"] : var.dev_account_id
  prod_account_id           = var.prod_account_id == "" ? local.account_json["prod_account_id"] : var.prod_account_id

  sso_provider_name             = var.sso_provider_name
  encodedsaml_metadata_document = var.encodedsaml_metadata_document
  sso_provider_description      = var.sso_provider_description             
}

resource "alicloud_ram_saml_provider" "management_account_idp" {
  saml_provider_name            = local.sso_provider_name
  encodedsaml_metadata_document = local.encodedsaml_metadata_document
  description                   = local.sso_provider_description
}


provider "alicloud" {
  alias = "log_account"
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.log_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}
resource "alicloud_ram_saml_provider" "log_account_idp" {
  provider                      = alicloud.log_account
  saml_provider_name            = local.sso_provider_name
  encodedsaml_metadata_document = local.encodedsaml_metadata_document
  description                   = local.sso_provider_description
}


provider "alicloud" {
  alias = "shared_service_account"
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.shared_service_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}
resource "alicloud_ram_saml_provider" "shared_service_account_idp" {
  provider                      = alicloud.shared_service_account
  saml_provider_name            = local.sso_provider_name
  encodedsaml_metadata_document = local.encodedsaml_metadata_document
  description                   = local.sso_provider_description
}


provider "alicloud" {
  alias = "security_account"
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.security_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}
resource "alicloud_ram_saml_provider" "security_account_idp" {
  provider                      = alicloud.security_account
  saml_provider_name            = local.sso_provider_name
  encodedsaml_metadata_document = local.encodedsaml_metadata_document
  description                   = local.sso_provider_description
}


provider "alicloud" {
  alias = "ops_account"
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.ops_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}
resource "alicloud_ram_saml_provider" "ops_account_idp" {
  provider                      = alicloud.ops_account
  saml_provider_name            = local.sso_provider_name
  encodedsaml_metadata_document = local.encodedsaml_metadata_document
  description                   = local.sso_provider_description
}


provider "alicloud" {
  alias = "dev_account"
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.dev_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}
resource "alicloud_ram_saml_provider" "dev_account_idp" {
  provider                      = alicloud.dev_account
  saml_provider_name            = local.sso_provider_name
  encodedsaml_metadata_document = local.encodedsaml_metadata_document
  description                   = local.sso_provider_description
}


provider "alicloud" {
  alias = "prod_account"
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", local.prod_account_id)
    session_name       = "AccountLandingZoneSetup"
    session_expiration = 999
  }
}
resource "alicloud_ram_saml_provider" "prod_account_idp" {
  provider                      = alicloud.prod_account
  saml_provider_name            = local.sso_provider_name
  encodedsaml_metadata_document = local.encodedsaml_metadata_document
  description                   = local.sso_provider_description
}

# terraform-alicloud-landing-zone-account-factory-role-based-sso

Terraform module which used to setup role-based SSO in new member account created by account factory.
This can be used as an account factory baseline module.

## Usage

```
module "role_based_ssso" {
  source = "terraform-alicloud-modules/landing-zone-account-factory-role-based-sso/alicloud"

  metadata_file_path = "./metadata.xml"
  ram_roles = [
    {
      name = "Admin"
      description = "Super admin"
      policies = [
        "AdministratorAccess"
      ]
    },
    {
      name = "LogAdmin"
      description = "Log service full access"
      policies = [
        "AliyunLogFullAccess"
      ]
    },
    {
      name = "NetworkAdmin"
      description = "VPC/SLB/CEN... full access"
      policies = [
        "AliyunVPCFullAccess",
        "AliyunNATGatewayFullAccess",
        "AliyunEIPFullAccess",
        "AliyunCENFullAccess",
        "AliyunSLBFullAccess"
      ]
    }
  ]
  saml_provider_name = "Okta"
  saml_provider_description = "Global IdP"
}
```

* `metadata_file_path` is the Metadata file exported from IdP
* `ram_roles` a list of roles used as role-based SSO.
  * `policies` only system policy allowed.
* `saml_provider_name` your IdP name.
* `saml_provider_description` your IdP description.
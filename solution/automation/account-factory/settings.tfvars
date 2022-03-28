# SSO Provider Name(Modify according to the actual situation)
sso_provider_name = "idp"

# Modify according to the actual situation
encodedsaml_metadata_document = "yourEncodedSAMLDocumentData"

ram_roles = {
  "roles"= [
    {
      # The name must be no more than 64 characters，English letters, numbers, or "-" are allowed
      "role_name"   = "admin"
      "description" = "Administrator role for member accounts"
    },
    {
      # The name must be no more than 64 characters，English letters, numbers, or "-" are allowed
      "role_name"   = "reader"
      "description" = "Reader role for member accounts"
    }
  ]
}

# step-create-user
user_name = "AutomationSecurity_DAT09"

# step-auth-authorize-role
policy_name = "AliyunContributor"
policy_document = <<EOF
{
  "Version": "1",
  "Statement": [
    {
      "Action": [
        "RAM:*"
      ],
      "Resource": [
        "*"
      ],
      "Effect": "Deny"
    },
    {
      "Action": [
        "*"
      ],
      "Resource": [
        "*"
      ],
      "Effect": "Allow"
    }
  ]
}
EOF
attach_roles = ["admin"]
attach_users = ["AutomationSecurity_DAT09"]
reader_name = "reader"
reader_policy_type = "System"
reader_policy_name = "AliyunLogReadOnlyAccess"

# step-network
vpc_name = "autovpc1"
vswitch_name = "autovsw1"
# vpc_cidr_block = "172.16.0.0/12"
# switch_cidr_block = "172.16.0.0/21"
# zone_id = "cn-hangzhou-b"
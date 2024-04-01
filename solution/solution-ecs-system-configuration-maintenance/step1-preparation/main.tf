provider "alicloud" {
  region = var.region
}

provider "alicloud" {
  alias  = "share_services"
  region = var.region
  assume_role {
    role_arn           = format("acs:ram::%s:role/ResourceDirectoryAccountAccessRole", var.share_services_account_id)
    session_name       = "WellArchitectedSolutionSetup"
    session_expiration = 999
  }
}

# create delegated administrator
resource "alicloud_resource_manager_delegated_administrator" "master" {
  account_id        = var.share_services_account_id
  service_principal = "ros.aliyuncs.com"
}

resource "alicloud_ram_policy" "share_services" {
  provider        = alicloud.share_services
  policy_name     = format("%sPolicy", var.oss_assume_role)
  policy_document = <<EOF
  {
    "Version": "1",
    "Statement": [
      {
        "Action": [
          "sts:AssumeRole"
        ],
        "Resource": "acs:ram:*:*:role/${var.oos_cross_account_assume_role}",
        "Effect": "Allow"
      }
    ]
  }
  EOF
}

resource "alicloud_ram_role" "share_services" {
  provider = alicloud.share_services
  name     = var.oss_assume_role
  document = <<EOF
  {
    "Statement": [
      {
        "Action": "sts:AssumeRole",
        "Effect": "Allow",
        "Principal": {
          "Service": [
            "oos.aliyuncs.com"
          ]
        }
      }
    ],
    "Version": "1"
  }
  EOF
}

resource "alicloud_ram_role_policy_attachment" "share_services" {
  provider    = alicloud.share_services
  policy_name = alicloud_ram_policy.share_services.policy_name
  policy_type = alicloud_ram_policy.share_services.type
  role_name   = alicloud_ram_role.share_services.name
}

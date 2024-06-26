variable "vpc_id" {}

terraform {
  required_providers {
    alicloud = {
      source  = "hashicorp/alicloud"
      version = "~> 1.1"
    }
  }
}

resource "alicloud_security_group" "group" {
  vpc_id = var.vpc_id
}

resource "alicloud_security_group_rule" "rule" {
  type              = "ingress"
  ip_protocol       = "all"
  nic_type          = "intranet"
  policy            = "accept"
  port_range        = "1/65535"
  priority          = 1
  security_group_id = alicloud_security_group.group.id
  cidr_ip           = "0.0.0.0/0"
}


output "sg_id" {
  value = alicloud_security_group.group.id
}


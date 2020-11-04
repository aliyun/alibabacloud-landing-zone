resource "alicloud_network_acl" "network_acl" {
  vpc_id      = var.vpc_id
  name        = var.network_acl_name
}

data "alicloud_vpcs" "vpcs_ds" {
  ids = [var.vpc_id]
}

resource "alicloud_network_acl_attachment" "network_acl_attachment" {
  network_acl_id = alicloud_network_acl.network_acl.id
  dynamic "resources" {
    for_each = var.vswitches

    content {
      resource_id   = resources.value.id
      resource_type = "VSwitch"
    }
  }
  
}

locals {
  rules = flatten(
    concat(
      [
        for vsw_key, vsw in var.vswitches : {
          id               = vsw.id
          protocol         = "all"
          port             = "-1/-1"
          source_cidr_ip   = vsw.cidr_block
          entry_type       = "custom"
          policy           = "accept"
        }
      ],
      [
        for vsw_key, vsw in var.vswitches_shared_services : {
          id               = vsw.id
          protocol         = "all"
          port             = "-1/-1"
          source_cidr_ip   = vsw.cidr_block
          entry_type       = "custom"
          policy           = "accept"
        }
      ],
      [
        for vsw_key, vsw in var.vswitches_dmz : {
          id               = vsw.id
          protocol         = "all"
          port             = "-1/-1"
          source_cidr_ip   = vsw.cidr_block
          entry_type       = "custom"
          policy           = "accept"
        }
      ]
    )
  )
}

resource "alicloud_network_acl_entries" "network_nacl_entries" {
  network_acl_id = alicloud_network_acl.network_acl.id

  dynamic "ingress" {
    for_each = {
      for rule in local.rules : "${rule.id}" => rule
    }
    content {
      protocol         = lookup(ingress.value, "protocol", null)
      port             = lookup(ingress.value, "port", null)
      source_cidr_ip   = lookup(ingress.value, "source_cidr_ip", null)
      entry_type       = lookup(ingress.value, "entry_type", null)
      policy           = lookup(ingress.value, "policy", null)
    }
  }
  ingress {
    protocol         = "all"
    port             = "-1/-1"
    source_cidr_ip   = "0.0.0.0"
    entry_type       = "custom"
    policy           = "drop"
  }
  dynamic "egress" {
    for_each = {
      for rule in local.rules : "${rule.id}" => rule
    }
    content {
      protocol              = lookup(egress.value, "protocol", null)
      port                  = lookup(egress.value, "port", null)
      destination_cidr_ip   = lookup(egress.value, "source_cidr_ip", null)
      entry_type            = lookup(egress.value, "entry_type", null)
      policy                = lookup(egress.value, "policy", null)
    }
  }
  egress {
    protocol              = "all"
    port                  = "-1/-1"
    destination_cidr_ip   = "0.0.0.0"
    entry_type            = "custom"
    policy                = "accept"
  }

  depends_on = [
    alicloud_network_acl_attachment.network_acl_attachment
  ]
}
locals {
  auth_required = var.shared_service_account_id != var.vpc_account_id ? true : false
}

resource "alicloud_resource_manager_service_linked_role" "service_linked_role" {
  provider     = alicloud.vpc_account
  count        = var.create_cen_linked_role ? 1 : 0
  service_name = "cen.aliyuncs.com"
}

resource "alicloud_cen_instance_grant" "cen_instance_grant" {
  provider          = alicloud.vpc_account
  count             = local.auth_required ? 1 : 0
  cen_id            = var.cen_instance_id
  cen_owner_id      = var.shared_service_account_id
  child_instance_id = var.vpc_id

  depends_on = [alicloud_resource_manager_service_linked_role.service_linked_role]
}

resource "alicloud_cen_transit_router_vpc_attachment" "vpc_attachment" {
  provider          = alicloud.shared_service_account
  cen_id            = var.cen_instance_id
  transit_router_id = var.cen_transit_router_id
  vpc_id            = var.vpc_id
  vpc_owner_id      = var.vpc_account_id

  zone_mappings {
    zone_id    = var.primary_vswitch.zone_id
    vswitch_id = var.primary_vswitch.vswitch_id
  }

  zone_mappings {
    zone_id    = var.secondary_vswitch.zone_id
    vswitch_id = var.secondary_vswitch.vswitch_id
  }

  transit_router_attachment_name        = var.transit_router_attachment_name
  transit_router_attachment_description = var.transit_router_attachment_desc

  timeouts {
    create = "15m"
    update = "15m"
    delete = "30m"
  }

  depends_on = [
    alicloud_resource_manager_service_linked_role.service_linked_role, alicloud_cen_instance_grant.cen_instance_grant
  ]
}

data "alicloud_cen_transit_router_route_tables" "route_tables" {
  transit_router_id = var.cen_transit_router_id
}

locals {
  default_route_table_id = data.alicloud_cen_transit_router_route_tables.route_tables.tables.0.transit_router_route_table_id
  vpc_attachment_id      = alicloud_cen_transit_router_vpc_attachment.vpc_attachment.transit_router_attachment_id
}

resource "alicloud_cen_transit_router_route_table_association" "route_table_association" {
  count                         = var.route_table_association_enabled ? 1 : 0
  transit_router_route_table_id = local.default_route_table_id
  transit_router_attachment_id  = local.vpc_attachment_id
}

resource "alicloud_cen_transit_router_route_table_propagation" "route_table_propagation" {
  count                         = var.route_table_propagation_enabled ? 1 : 0
  transit_router_route_table_id = local.default_route_table_id
  transit_router_attachment_id  = local.vpc_attachment_id
}


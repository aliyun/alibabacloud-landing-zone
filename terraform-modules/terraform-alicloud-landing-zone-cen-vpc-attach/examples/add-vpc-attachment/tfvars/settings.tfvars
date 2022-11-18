region = "cn-shanghai"

shared_service_account_id = "1140931609457592"
cen_instance_id           = "cen-lsx8u609g2s4o71kww"
cen_transit_router_id     = "tr-uf6vpyd9yp4lyeu70n3yw"

vpc_account_id = "1333131609463815"
vpc_id         = "vpc-uf6f3w8xcehlne2o1n9xh"

create_cen_linked_role         = false
transit_router_attachment_name = "attach-vpc-test"
transit_router_attachment_desc = "attach-vpc-test"

primary_vswitch = {
  vswitch_id = "vsw-uf6riwzpnwafohhd7nzlp"
  zone_id    = "cn-shanghai-f"
}

secondary_vswitch = {
  vswitch_id = "vsw-uf61l1y7zjzw7wtbnrx1l"
  zone_id    = "cn-shanghai-g"
}

route_table_association_enabled = true
route_table_propagation_enabled = true


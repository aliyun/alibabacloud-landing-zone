cen_instance_name = "lz-cen"
network_cidr = "10.0.0.0/8"
dmz_vpc_region = "cn-hangzhou"
dmz_vpc_cidr = "10.0.1.0/24"
dmz_egress_eip_name = "lz-dmz-eip"
dmz_egress_nat_gateway_name = "lz-dmz-nat"
dmz_vswitch = [ {
  vswitch_cidr = "10.0.1.0/26"
  vswitch_description = "lz-dmz-vsw-1"
  vswitch_name = "lz-dmz-vsw-1"
  zone_id = "cn-hangzhou-h"
}, {
  vswitch_cidr = "10.0.1.128/26"
  vswitch_description = "lz-dmz-vsw-2"
  vswitch_name = "lz-dmz-vsw-2"
  zone_id = "cn-hangzhou-i"
} ]
region = "cn-hangzhou"
dmz_vpc_cidr_block = "10.1.0.0/16"
dmz_vswitch_list = [
  {
    vswitch_cidr = "10.1.0.0/24"
    zone_id = "cn-hangzhou-h"
  },
  {
    vswitch_cidr = "10.1.1.0/24"
    zone_id = "cn-hangzhou-j"
  }
]
dmz_vswitch_for_tr = [
  {
    vswitch_cidr = "10.0.0.0/29"
    zone_id = "cn-hangzhou-h"
  },
  {
    vswitch_cidr = "10.0.0.8/29"
    zone_id = "cn-hangzhou-j"
  }
]
dmz_vswitch_for_nat_gateway = {
  vswitch_cidr = "10.0.0.64/26"
  zone_id = "cn-hangzhou-h"
}
dmz_egress_eip_count = 1
dmz_enable_common_bandwidth_package = true
dmz_common_bandwidth_package_bandwidth = 5
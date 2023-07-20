output "dmz_vpc_id" {
  value = module.dmz_vpc.dmz_vpc_id
}

output "dmz_vswitches" {
  value = [
    for idx, vsw in module.dmz_vpc.dmz_vswitches :
    {
      ZoneId = vsw.zone_id
      VswitchId = vsw.vswitch_id
    }
  ]
}
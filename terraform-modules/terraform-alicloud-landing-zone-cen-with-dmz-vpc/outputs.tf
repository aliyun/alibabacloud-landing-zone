output "cen_account_id" {
  value = data.alicloud_account.cen.id
}

output "dmz_vpc_account_id" {
  value = data.alicloud_account.dmz_vpc.id
}

output "dmz_vpc_id" {
  value = alicloud_vpc.dmz_vpc.id
}

output "dmz_vswitches" {
  value = [
    for idx, vsw in alicloud_vswitch.dmz_vswitch :
    {
      vswitch_id = vsw.id
      zone_id = vsw.zone_id
    }
  ]
}
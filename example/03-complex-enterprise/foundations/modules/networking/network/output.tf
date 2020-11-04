output "vpc_shared_service_id" {
  value = alicloud_vpc.vpc_shared_service.id
}

output "vpc_dmz_id" {
  value = alicloud_vpc.vpc_dmz.id
}

output "vpc_production_id" {
  value = alicloud_vpc.vpc_production.id
}

output "vpc_non_production_id" {
  value = alicloud_vpc.vpc_non_production.id
}

output "cen_instance_id" {
  value = alicloud_cen_instance.cen.id
}

output "vswitches_shared_services" {
  value = alicloud_vswitch.shared_service_vswitches
}

output "vswitches_dmz" {
  value = alicloud_vswitch.dmz_vswitches
}
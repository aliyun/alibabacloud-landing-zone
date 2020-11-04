output "cen_instance_id" {
  value = module.network_main.cen_instance_id
}

output "vswitches_shared_services" {
  value = module.network_main.vswitches_shared_services
}

output "vswitches_dmz" {
  value = module.network_main.vswitches_dmz
}

output "vpc_production_id" {
  value = module.network_main.vpc_production_id
}

output "vpc_non_production_id" {
  value = module.network_main.vpc_non_production_id
}

output "vpc_shared_service_id" {
  value = module.network_main.vpc_shared_service_id
}

output "vpc_dmz_id" {
  value = module.network_main.vpc_dmz_id
}

output "cloudfirewall_setting" {
  value = {
    cen_instance_id = module.network_main.cen_instance_id
    vpc_shared_services_cidr_block = var.network_settings.vpc_shared_services.cidr_block
    vpc_dmz_cidr_block = var.network_settings.vpc_dmz.cidr_block
    vpc_production_cidr_block = var.network_settings.vpc_production.cidr_block
    vpc_non_production_cidr_block = var.network_settings.vpc_non_production.cidr_block
    vpc_production_id = module.network_main.vpc_production_id
    vpc_non_production_id = module.network_main.vpc_non_production_id
    vpc_shared_service_id = module.network_main.vpc_shared_service_id
    vpc_dmz_id = module.network_main.vpc_dmz_id
  }
}
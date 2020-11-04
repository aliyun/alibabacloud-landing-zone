output "foundations" {
  value = {
    master_uid = data.alicloud_account.current_account.id
    shared_services_uid = alicloud_resource_manager_account.rd_account_SharedServices.id
    rd_folder_application_id = alicloud_resource_manager_folder.rd_folder_Business.id
    cloudfirewall = module.networking.cloudfirewall_setting
    networking = {
      cen_instance_id = module.networking.cen_instance_id
      vswitches_shared_services = module.networking.vswitches_shared_services
      vswitches_dmz = module.networking.vswitches_dmz
      vpc_production_id = module.networking.vpc_production_id
      vpc_non_production_id = module.networking.vpc_non_production_id
    }
  }
}
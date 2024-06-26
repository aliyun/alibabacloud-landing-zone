output "shared_service_account_vpc_id" {
  value = module.shared_service_account_vpc.vpc_id
}

output "shared_service_account_vsw_tr1_id" {
  value = module.shared_service_account_vpc.vsw1_id
}

output "shared_service_account_vsw_tr2_id" {
  value = module.shared_service_account_vpc.vsw2_id
}

output "shared_service_account_vsw1_id" {
  value = module.shared_service_account_vpc.vsw3_id
}

output "shared_service_account_vsw2_id" {
  value = module.shared_service_account_vpc.vsw4_id
}

output "dev_account_vpc_id" {
  value = module.dev_account_vpc.vpc_id
}

output "dev_account_vsw_tr1_id" {
  value = module.dev_account_vpc.vsw1_id
}

output "dev_account_vsw_tr2_id" {
  value = module.dev_account_vpc.vsw2_id
}

output "dev_account_vsw1_id" {
  value = module.dev_account_vpc.vsw3_id
}

output "dev_account_vsw2_id" {
  value = module.dev_account_vpc.vsw4_id
}


output "prod_account_vpc_id" {
  value = module.prod_account_vpc.vpc_id
}

output "prod_account_vsw_tr1_id" {
  value = module.prod_account_vpc.vsw1_id
}

output "prod_account_vsw_tr2_id" {
  value = module.prod_account_vpc.vsw2_id
}

output "prod_account_vsw1_id" {
  value = module.prod_account_vpc.vsw3_id
}

output "prod_account_vsw2_id" {
  value = module.prod_account_vpc.vsw4_id
}

output "ops_account_vpc_id" {
  value = module.ops_account_vpc.vpc_id
}

output "ops_account_vsw_tr1_id" {
  value = module.ops_account_vpc.vsw1_id
}

output "ops_account_vsw_tr2_id" {
  value = module.ops_account_vpc.vsw2_id
}

output "ops_account_vsw1_id" {
  value = module.ops_account_vpc.vsw3_id
}

output "ops_account_vsw2_id" {
  value = module.ops_account_vpc.vsw4_id
}

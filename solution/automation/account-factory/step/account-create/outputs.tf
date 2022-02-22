# outputs.tf https://learn.hashicorp.com/tutorials/terraform/outputs
output "account_id" {
  value = alicloud_resource_manager_account.rd_account_app.id
}
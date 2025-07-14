provider "alicloud" {
  region = var.region
}


module "adduser" {
  source               = "../../modules/cloudsso-create-user"
  cloudsso_directory_name = var.cloudsso_directory_name
  cloudsso_user = var.cloudsso_user
}
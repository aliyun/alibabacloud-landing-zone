provider "alicloud" {
  region = "cn-shanghai"
}

module "test" {
  source = "../../"
  
  var1 = var.var1
}
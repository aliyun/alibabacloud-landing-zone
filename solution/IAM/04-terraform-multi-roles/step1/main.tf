provider "alicloud" {
  access_key = var.access_key
  secret_key = var.secret_key
  region = var.region
}

# assumerole到成员账号
resource "local_file" "step2-main" {
  content = templatefile("${path.module}/step2.tmpl", {
    access_key = var.access_key,
    secret_key = var.secret_key,
    region = var.region,
    ram_roles = var.ram_roles
  })
  filename = "${path.module}/../step2/main.tf"
}
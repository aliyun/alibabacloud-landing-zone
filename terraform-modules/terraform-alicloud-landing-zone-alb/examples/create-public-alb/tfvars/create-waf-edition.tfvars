region     = "cn-shanghai"
dmz_vpc_id = "vpc-uf64jvvvabr84damwjp9q"

alb_instance_spec = {
    protocol               = "HTTP"
    address_type           = "Internet"
    address_allocated_mode = "Fixed"
    load_balancer_edition  = "StandardWithWaf"
    tags                   = { createdBy : "Terraform" }
}

alb_instance_deploy_config = {
  load_balancer_name = "alb-dmz-ingress"
  zone_1_id          = "cn-shanghai-f"
  vswitch_1_id       = "vsw-uf6hsm7zaupj72bekrctf"

  zone_2_id    = "cn-shanghai-g"
  vswitch_2_id = "vsw-uf6xwi8lz10wc60kb5mzd"
}

# TF不支持，目前无法使用跨VPC挂载IP类型服务器组功能
#server_group_backend_servers = [
#  {
#    server_type = "Ip"
#    server_id   = "i-uf6ambutcn90srdw6kw1"
#    server_ip   = "172.16.10.50"
#    description = "backend-server1"
#    weight      = 100
#    port        = 80
#  }
#]


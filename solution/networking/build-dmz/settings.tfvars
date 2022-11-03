shared_service_account_id = "1140931609457592"
biz_vpc_1_account_id = "1140931609457592"
biz_vpc_2_account_id = "1140931609457592"

region                    = "cn-shanghai"
dmz_vpc_id                = "vpc-uf64jvvvabr84damwjp9q"

nat_gateway_config = {
  name                  = "nat-gateway-dmz-egress"
  vswitch_id            = "vsw-uf6hsm7zaupj72bekrctf"
  snat_source_cidr_list = ["172.16.10.0/24"]
}

transit_router_id     = "tr-uf6vpyd9yp4lyeu70n3yw"
cen_attach_id_dmz_vpc = "tr-attach-79nkaodj9a1qhblksf"

biz_vpc_1_id            = "vpc-uf6ws6nb77jl1xp0fb4ow"
cen_attach_id_biz_vpc_1 = "tr-attach-5ava0vopch6uomfype"
biz_vpc_1_cidr          = "172.16.0.0/24"

biz_vpc_2_id            = "vpc-uf6l1gunod4fltxgjuwkj"
cen_attach_id_biz_vpc_2 = "tr-attach-0d2r8rdctys8mq7o2m"


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

# 应该是ALB创建完成后自动查询回源并自动配置，不需要该配置项。但TF不支持查询，这里仅用于示例测试
alb_back_to_source_route = [
  "100.121.111.192/26", "100.121.112.0/26", "100.121.112.64/26", "100.121.113.128/26", "100.117.147.128/26",
  "100.117.147.192/26", "100.117.147.64/26", "100.121.111.128/26"
]


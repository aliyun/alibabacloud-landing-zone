alicloud_access_key = "LTAI5tFM**************"

alicloud_secret_key = "5oBEklBm*********************"

region = "cn-hangzhou"

vpc_cidr = "192.168.0.0/16"

# Network Configuration
node_vswitches = [
    {
        cidr = "192.168.0.0/19"
        zone_id = "cn-hangzhou-h"
    },
    {
        cidr = "192.168.64.0/19"
        zone_id = "cn-hangzhou-g"
    }
]

pod_vswitches = [
    {
        cidr = "192.168.32.0/19"
        zone_id = "cn-hangzhou-h"
    },
    {
        cidr = "192.168.96.0/19"
        zone_id = "cn-hangzhou-g"
    }
]

eip_bandwidth = 10

# ACK Cluster Configuration
service_cidr = "172.21.0.0/20"

ack_version = "1.22.10-aliyun.1"

# Node Pool Configuration
desired_size = 1

worker_instance_types = [ "ecs.c5.xlarge" ]

load_balancer_spec = "slb.s1.small"

ack_key_pair_name = "test-key-pair"
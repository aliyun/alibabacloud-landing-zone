locals {
  ack_managed_cluster_name = var.ack_managed_cluster_name
  ack_node_pool_name = join("-", [local.ack_managed_cluster_name, "node-pool"])
  ack_vpc_name = join("-", [local.ack_managed_cluster_name, "vpc"])
  ack_nat_gateway_name = join("-", [local.ack_managed_cluster_name, "nat-gateway"])
  ack_eip_name = join("-", [local.ack_managed_cluster_name, "eip"])
}

resource "alicloud_vpc" "default" {
    vpc_name = local.ack_vpc_name
    cidr_block = var.vpc_cidr
}

resource "alicloud_vswitch" "node_vswitches" {
    count = length(var.node_vswitches)
    vswitch_name = join("-", [local.ack_managed_cluster_name, "node-vswitches", count.index])
    vpc_id = alicloud_vpc.default.id
    cidr_block = var.node_vswitches[count.index].cidr
    zone_id = var.node_vswitches[count.index].zone_id
}

resource "alicloud_vswitch" "pod_vswitches" {
    count = length(var.pod_vswitches)
    vswitch_name = join("-", [local.ack_managed_cluster_name, "pod-vswitches", count.index])
    vpc_id = alicloud_vpc.default.id
    cidr_block = var.pod_vswitches[count.index].cidr
    zone_id = var.pod_vswitches[count.index].zone_id
}

resource "alicloud_nat_gateway" "default" {
    vpc_id = alicloud_vpc.default.id
    nat_gateway_name = local.ack_nat_gateway_name
    nat_type = "Enhanced"
    vswitch_id = alicloud_vswitch.pod_vswitches[0].id
    internet_charge_type = "PayByLcu"
    payment_type = "PayAsYouGo"
}

resource "alicloud_eip_address" "default" {
    address_name = local.ack_eip_name
    bandwidth = var.eip_bandwidth
    isp = "BGP"
    internet_charge_type = "PayByDominantTraffic"
    payment_type = "PayAsYouGo"
}

resource "alicloud_eip_association" "default" {
    allocation_id = alicloud_eip_address.default.id
    instance_id = alicloud_nat_gateway.default.id
    instance_type = "Nat"
}

resource "alicloud_snat_entry" "node_snat_entry" {
    count = length(alicloud_vswitch.node_vswitches)
    snat_table_id = alicloud_nat_gateway.default.snat_table_ids
    source_vswitch_id = alicloud_vswitch.node_vswitches[count.index].id
    snat_ip = alicloud_eip_address.default.*.ip_address[floor(count.index / length(alicloud_vswitch.node_vswitches))]
    depends_on = [ alicloud_eip_association.default ]
}

resource "alicloud_snat_entry" "pod_snat_entry" {
    count = length(alicloud_vswitch.pod_vswitches)
    snat_table_id = alicloud_nat_gateway.default.snat_table_ids
    source_vswitch_id = alicloud_vswitch.pod_vswitches[count.index].id
    snat_ip = alicloud_eip_address.default.*.ip_address[floor(count.index / length(alicloud_vswitch.pod_vswitches))]
    depends_on = [ alicloud_eip_association.default ]
}

resource "alicloud_cs_managed_kubernetes" "default" {
    name = local.ack_managed_cluster_name
    version = var.ack_version
    timezone = var.timezone
    cluster_spec = "ack.pro.small"
    node_cidr_mask = 24
    service_cidr = var.service_cidr

    load_balancer_spec = var.load_balancer_spec
    new_nat_gateway = false
    worker_vswitch_ids = alicloud_vswitch.node_vswitches.*.id
    pod_vswitch_ids = alicloud_vswitch.pod_vswitches.*.id
    is_enterprise_security_group = true
    
    deletion_protection = true
    install_cloud_monitor = true
    control_plane_log_components = ["apiserver", "kcm", "scheduler"]

    addons {
        name = "terway-eniip"
    }

    addons {
        name = "csi-plugin"
    }
        
    addons {
        name = "csi-provisioner"
    }

    addons {
        name = "logtail-ds"
        config = "{\"IngressDashboardEnabled\":\"true\"}"
    }

    addons {
        name = "alb-ingress-controller"
    }

    addons {
        name = "nginx-ingress-controller"
        disabled = "true"
    }

    addons {
        name = "arms-prometheus"
    }

    addons {
        name = "ack-node-problem-detector"
        config = "{\"sls_project_name\":\"\"}"
    }

    runtime = {
        name = "containerd"
        version = "1.5.10"
    }
}

resource "alicloud_cs_kubernetes_node_pool" "default" {
    name = local.ack_node_pool_name
    cluster_id = alicloud_cs_managed_kubernetes.default.id
    vswitch_ids = alicloud_vswitch.node_vswitches.*.id
    instance_types = var.worker_instance_types
    image_type = "AliyunLinux"
    
    instance_charge_type = "PostPaid"

    system_disk_category = var.disk_category
    system_disk_size = var.system_disk_size
    key_name = var.ack_key_pair_name

    desired_size = var.desired_size

    data_disks {
      category = var.disk_category
      size = var.data_disk_size
    }
}
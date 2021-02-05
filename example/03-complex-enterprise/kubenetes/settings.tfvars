access_key = ""
secret_key = ""
region     = "cn-shanghai"

foundations = {
  app1_uid   = "123123123123123"
}

applications_cluster_setting = {
  k8s_cluster = {
    k8s_number            = 1
    k8s_name              = "k8s-demo"
    cluster_spec          = "ack.standard"
    worker_vswitch_ids    = ["vsw-uf6adfsdfxxxxmcvl"]
    pod_vswitch_ids       = ["vsw-uf6sdfsdfxxxxtret"]
    worker_instance_types = ["ecs.g5.xlarge"]
    worker_number         = 2
    install_cloud_monitor = true
    cpu_policy            = "none"
    proxy_mode            = "ipvs"
    node_login_password   = "Test12345"
    pod_cidr              = "172.20.0.0/16"
    service_cidr          = "172.21.0.0/20"
    cluster_addons = [
      {
        name = "terway-eniip"
        config = ""
      },
      {
        name = "csi-plugin"
        config = ""
      },
      {
        name = "csi-provisioner"
        config = ""
      },
      {
        name = "logtail-ds"
        config = "{\"IngressDashboardEnabled\":\"true\",\"sls_project_name\":\"your-sls-project-name\"}"
      },
      {
        name = "ack-node-problem-detector"
        config = "{\"sls_project_name\":\"\"}"
      },
      {
        name = "nginx-ingress-controller"
        config = "{\"IngressSlbNetworkType\":\"intranet\"}"
      }
    ]
  }

  container_images={
    namespace = "test"
    repo_name = "k8sapp"
  }
}

network_settings={
  vpc_id                                         = "vpc-uf6xxxxxx" 
  network_enabled                                = false
  external_port                                  = "any"
  eip_id                                         = "eip-uf6xxxxx"
  nat_id                                         = "ngw-uf6xxxxx"
  ip_protocol                                    = "any"
  internal_ip                                    = "10.10.10.10"
  internal_port                                  = "any"
}

        
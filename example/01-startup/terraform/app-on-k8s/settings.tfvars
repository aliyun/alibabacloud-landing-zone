k8s_name              = "k8s-demo"
cluster_spec          = "ack.standard"
worker_instance_types = ["ecs.g5.xlarge"]
worker_number         = 2
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
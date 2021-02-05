
variable "cluster_spec" {
}

variable "k8s_name" {
}

variable "worker_instance_types" {
}

variable "worker_number" {
    default = 2
}

variable "proxy_mode" {
    default = "ipvs"
}

variable "node_login_password" {
    default = "Test12345"
}

variable "pod_cidr" {}

variable "service_cidr" {}

variable "cluster_addons" {}

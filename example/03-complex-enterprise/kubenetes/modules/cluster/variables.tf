
variable "cluster_spec" {
}

variable "pod_cidr" {
}

variable "k8s_number" {
}

variable "k8s_name" {
}


variable "worker_vswitch_ids" {
}

variable "pod_vswitch_ids" {
}

variable "worker_instance_types" {
}

variable "worker_number" {
    default = 3
}

variable "install_cloud_monitor" {
    default = true
}

variable "proxy_mode" {
    default = "ipvs"
}

variable "node_login_password" {
    default = "Test12345"
}

variable "service_cidr" {}

variable "cluster_addons" {}

# variable "namespace" {}

# variable "repo_name" {}

variable "default_visibility" {
    default="PUBLIC"
}

variable "summary" {
    default="k8s application repo"
}

variable "repo_type" {
    default="PUBLIC"
}



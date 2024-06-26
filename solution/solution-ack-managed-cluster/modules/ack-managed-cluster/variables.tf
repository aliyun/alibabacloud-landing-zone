# Network variables
variable "vpc_cidr" {
  description = "The cidr block used to create a new vpc."
}

variable "node_vswitches" {
  description = "Switch object used to create serval new vswitches for node."

  type = list(object({
    cidr = string
    zone_id = string
  }))
}

variable "pod_vswitches" {
  description = "Switch object used to create serval new vswitches for pod."

  type = list(object({
      cidr = string
      zone_id = string
  }))
}

variable "eip_bandwidth" {
  description = "The maximum bandwidth of the EIP."
}


# ACK variables
variable "ack_managed_cluster_name" {
  description = "The name of ack managed cluster."  
}

variable "load_balancer_spec" {
  description = "The specification of load balancer."
}

variable "service_cidr" {
  description = "The cidr block for the service network."
}

variable "timezone" {
  description = "Time Zone. This field cannot be modifed after creation."
  default = "Asia/Shanghai"
}

variable "ack_version" {
  description = "The version of ACK cluster."
}

# Node pool variables
variable "desired_size" {
  description = "The desired size of node pool"
}

variable "ack_key_pair_name" {
  description = "The key pair name."
}

variable "worker_instance_types" {
  description = "The instance type of worker node."
  type = list(string)
}

variable "disk_category" {
  description = "The system and data disk category of worker node."
  default = "cloud_essd"
}

variable "system_disk_size" {
  description = "The size of system disk."
  default = 40
}

variable "data_disk_size" {
  description = "The size of data disk."
  default = 50
}
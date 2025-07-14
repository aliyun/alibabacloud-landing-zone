variable "vpc_id" {
  description = "The ID of the VPC."
  type        = string
}

variable "resource_group_id" {
  description = "The ID of the resource group."
  type        = string
  default     = null
}

variable "security_group_name" {
  description = "The name of the new security group."
  type        = string
  default     = "sg-tf-default"
}

variable "security_group_desc" {
  description = "Description of the new security group."
  type        = string
  default     = "sg-tf-default"
}

variable "security_group_rule" {
  type = list(object({
    type        = string
    ip_protocol = string
    nic_type    = string
    policy      = string
    port_range  = string
    priority    = number
    cidr_ip     = string
  }))

  default = [
    {
      type        = "ingress"
      ip_protocol = "tcp"
      nic_type    = "intranet"
      policy      = "accept"
      port_range  = "1/65535"
      priority    = 1
      cidr_ip     = "0.0.0.0/0"
    }
  ]
}

variable "ecs_instance_password" {}

variable "ecs_instance_deploy_config" {
  type = list(object({
    zone_id       = string
    vswitch_id    = string
    instance_name = string
    host_name     = string
    description   = string
  }))
}

variable "ecs_instance_spec" {
  type = object({
    instance_type              = string
    system_disk_category       = string
    image_id                   = string
    instance_charge_type       = string
    period_unit                = string
    period                     = number
    internet_max_bandwidth_out = number
    tags                       = map(string)
    volume_tags                = map(string)
  })

  default = {
    instance_type              = "ecs.t5-lc1m1.small"
    system_disk_category       = "cloud_efficiency"
    image_id                   = "centos_8_5_x64_20G_alibase_20220428.vhd"
    instance_charge_type       = "PostPaid"
    period_unit                = "Month"
    period                     = 1
    internet_max_bandwidth_out = 0
    tags                       = { createdBy : "Terraform" }
    volume_tags                = { createdBy : "Terraform" }
  }
}

variable "vpc_id" {
  description = "VPC ID for NAT gateway."
  type        = string
  default     = ""
}

variable "vswitch_id" {
  description = "VSwitch ID for NAT gateway."
  type        = string
}

variable "name" {
  description = "NAT Gateway name."
  type        = string
  default     = "nat-gateway-dmz"
}

variable "tags" {
  description = "Tag for NAT Gateway"
  type        = object({})
  default     = {}
}

variable "association_eip_id_list" {
  type        = list(string)
  description = "EIP instance ID associated with NAT gateway."
}

variable "network_type" {
  description = "Indicates the type of the created NAT gateway.Valid values internet and intranet. internet: Internet NAT Gateway. intranet: VPC NAT Gateway."
  type        = string
  default     = "internet"
}

variable "payment_type" {
  description = "The billing method of the NAT gateway."
  type        = string
  default     = "PayAsYouGo"
}

variable "period" {
  description = "The duration that you will buy the resource, in month. It is valid when payment_type is Subscription."
  type        = number
  default     = null
}

variable "snat_ip_list" {
  description = "The SNAT IP address."
  type        = list(string)
}

variable "snat_source_cidr_list" {
  description = "Source address segment for SNAT."
  type        = list(string)
}







